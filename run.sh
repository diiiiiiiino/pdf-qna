#!/usr/bin/env bash
set -euo pipefail

# 사용법 출력
usage() {
    echo "사용법: ./run.sh --notion <URL> [--single] <pdf파일경로...>"
    echo ""
    echo "필수:"
    echo "  --notion <URL>    Notion 페이지 URL"
    echo ""
    echo "옵션:"
    echo "  --single          단건 모드 (여러 PDF 가능, 기본: 챕터별 분리)"
    exit 1
}

if [ $# -lt 1 ]; then
    usage
fi

# 인자 파싱
NOTION_PAGE=""
SPLIT_MODE="split"
PDF_PATHS=()

while [ $# -gt 0 ]; do
    case "$1" in
        --single)
            SPLIT_MODE="single"
            shift
            ;;
        --notion)
            if [ -z "${2:-}" ]; then
                echo "오류: --notion 뒤에 URL을 지정해주세요."
                exit 1
            fi
            NOTION_PAGE="$2"
            shift 2
            ;;
        --help|-h)
            usage
            ;;
        *)
            PDF_PATHS+=("$1")
            shift
            ;;
    esac
done

if [ ${#PDF_PATHS[@]} -eq 0 ]; then
    echo "오류: PDF 파일 경로를 지정해주세요."
    usage
fi

if [ -z "$NOTION_PAGE" ]; then
    echo "오류: --notion <URL> 옵션은 필수입니다."
    usage
fi

now() { date '+%H:%M:%S'; }
elapsed() {
    local start=$1
    local end
    end=$(date +%s)
    local diff=$((end - start))
    local min=$((diff / 60))
    local sec=$((diff % 60))
    if [ $min -gt 0 ]; then
        echo "${min}분 ${sec}초"
    else
        echo "${sec}초"
    fi
}
log() { echo "[$(now)] $*"; }

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUT_DIR="out"

TOTAL_START=$(date +%s)
log "모드: ${SPLIT_MODE} ($([ "$SPLIT_MODE" = "split" ] && echo "챕터별 분리" || echo "단건 ${#PDF_PATHS[@]}개"))"
log "파일: ${PDF_PATHS[*]}"
echo ""

# ===== Step 1: PDF 텍스트 추출 =====
STEP1_START=$(date +%s)
echo "[STEP:1:START] PDF 텍스트 추출"
mkdir -p "$OUT_DIR"

VALID_PDFS=()
for pdf in "${PDF_PATHS[@]}"; do
    log "추출 중: $(basename "$pdf")"
    RESULT=$(./gradlew run --args="'${pdf}' '${OUT_DIR}'" --quiet 2>&1 || true)
    echo "$RESULT"
    if echo "$RESULT" | grep -q "^SKIP:IMAGE_PDF:"; then
        log "⚠️  건너뜀: $(basename "$pdf") (이미지 기반 PDF)"
    else
        VALID_PDFS+=("$pdf")
    fi
done

if [ ${#VALID_PDFS[@]} -eq 0 ]; then
    log "처리할 수 있는 텍스트 기반 PDF가 없습니다. 텍스트가 포함된 PDF를 선택해주세요."
    echo "[STEP:1:DONE] ($(elapsed "$STEP1_START"))"
    echo "[STEP:ALL:DONE] (총 $(elapsed "$TOTAL_START"))"
    exit 0
fi

PDF_PATHS=("${VALID_PDFS[@]}")

echo "[STEP:1:DONE] ($(elapsed "$STEP1_START"))"

# ===== Step 2: Q&A 생성 + Notion 업로드 =====
STEP2_START=$(date +%s)
echo "[STEP:2:START] Q&A 생성 + Notion 업로드"

PIDS=()

if [ "$SPLIT_MODE" = "split" ]; then
    PDF_PATH="${PDF_PATHS[0]}"
    BASENAME="$(basename "$PDF_PATH" .pdf)"
    EXTRACTED="${SCRIPT_DIR}/${OUT_DIR}/${BASENAME}.md"

    log "챕터별 QnA 생성 + Notion 업로드 시작 (서브에이전트 병렬 방식)"
    (
        echo "/qna

[대상 파일]
${EXTRACTED}

[Notion 페이지]
${NOTION_PAGE}" | claude -p --model sonnet --dangerously-skip-permissions
    ) &
    PIDS+=($!)
else
    for pdf in "${PDF_PATHS[@]}"; do
        BASENAME="$(basename "$pdf" .pdf)"
        EXTRACTED="${SCRIPT_DIR}/${OUT_DIR}/${BASENAME}.md"
        EXTRACTED_CONTENT="$(cat "$EXTRACTED")"
        (
            echo "/qna-single

[문서 내용]
${EXTRACTED_CONTENT}

[Notion 페이지]
${NOTION_PAGE}" | claude -p --model sonnet --dangerously-skip-permissions
        ) &
        PIDS+=($!)
        log "  시작: $(basename "$pdf")"
    done
fi

# 모든 병렬 작업 완료 대기
FAIL_COUNT=0
for pid in "${PIDS[@]}"; do
    if ! wait "$pid"; then
        FAIL_COUNT=$((FAIL_COUNT + 1))
    fi
done

if [ "$FAIL_COUNT" -gt 0 ]; then
    log "경고: ${FAIL_COUNT}개 작업 실패"
fi

echo "[STEP:2:DONE] ($(elapsed "$STEP2_START"))"

echo "[STEP:ALL:DONE] (총 $(elapsed "$TOTAL_START"))"
log "모든 작업 완료!"