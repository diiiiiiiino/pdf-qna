#!/usr/bin/env bash
set -euo pipefail

# 사용법 출력
usage() {
    echo "사용법: ./run.sh [--notion <URL> | --md [--md-dir <경로>]] [--single] [--summary] [--from-md] <파일경로...>"
    echo ""
    echo "출력 (하나 이상 필수):"
    echo "  --notion <URL>    Notion 페이지 URL에 업로드"
    echo "  --md              로컬 마크다운 파일로 생성"
    echo "  --md-dir <경로>   MD 파일 출력 디렉토리 (기본: out/{PDF명}_{task}/)"
    echo ""
    echo "옵션:"
    echo "  --single          단건 모드 (여러 PDF 가능, 기본: 챕터별 분리)"
    echo "  --summary         요약 모드 (기본: QnA 모드)"
    echo "  --convert         원문 변환 모드 (PDF → 가독성 좋은 마크다운)"
    echo "  --deck            덱 모드 (naki용 플래시카드 JSON 생성)"
    echo "  --from-md         이미 추출된 MD 파일을 입력으로 사용 (Step 1 추출 스킵)"
    exit 1
}

if [ $# -lt 1 ]; then
    usage
fi

# 인자 파싱
NOTION_PAGE=""
MD_MODE="false"
MD_DIR_CUSTOM=""
SPLIT_MODE="split"
TASK_TYPE="qna"
FROM_MD="false"
PDF_PATHS=()

while [ $# -gt 0 ]; do
    case "$1" in
        --single)
            SPLIT_MODE="single"
            shift
            ;;
        --summary)
            TASK_TYPE="summary"
            shift
            ;;
        --convert)
            TASK_TYPE="convert"
            shift
            ;;
        --deck)
            TASK_TYPE="deck"
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
        --md)
            MD_MODE="true"
            shift
            ;;
        --md-dir)
            if [ -z "${2:-}" ]; then
                echo "오류: --md-dir 뒤에 경로를 지정해주세요."
                exit 1
            fi
            MD_DIR_CUSTOM="$2"
            shift 2
            ;;
        --from-md)
            FROM_MD="true"
            shift
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

if [ -z "$NOTION_PAGE" ] && [ "$MD_MODE" = "false" ]; then
    echo "오류: --notion <URL> 또는 --md 옵션 중 하나 이상을 지정해주세요."
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

# 입력 파일 → BASENAME (확장자 제거)
input_basename() {
    local p="$1"
    local b
    b="$(basename "$p")"
    case "$b" in
        *.pdf) echo "${b%.pdf}" ;;
        *.md)  echo "${b%.md}" ;;
        *)     echo "$b" ;;
    esac
}

# 입력 파일 → 추출된 MD 파일 경로
extracted_path() {
    local p="$1"
    if [ "$FROM_MD" = "true" ]; then
        # 이미 MD 파일이면 그대로 사용
        echo "$p"
    else
        local b
        b="$(input_basename "$p")"
        echo "${SCRIPT_DIR}/${SOURCE_DIR}/${b}.md"
    fi
}

# 입력 MD 경로 → 카테고리(source/ 하위 폴더명). 없으면 빈 문자열
input_category() {
    local p="$1"
    if [ "$FROM_MD" != "true" ]; then
        echo ""
        return
    fi
    local source_abs="${SCRIPT_DIR}/${SOURCE_DIR}"
    local rel="$p"
    if [[ "$rel" == "${source_abs}/"* ]]; then
        rel="${rel#${source_abs}/}"
    elif [[ "$rel" == "${SOURCE_DIR}/"* ]]; then
        rel="${rel#${SOURCE_DIR}/}"
    fi
    local parent
    parent="$(dirname "$rel")"
    if [ "$parent" = "." ] || [ -z "$parent" ]; then
        echo ""
    else
        echo "$parent"
    fi
}

# 결과 저장 디렉토리 계산: result/{task}/{category?}/{basename}
result_dir_for() {
    local basename="$1"
    local task="$2"
    local category="$3"
    if [ -n "$category" ]; then
        echo "${SCRIPT_DIR}/${RESULT_DIR}/${task}/${category}/${basename}"
    else
        echo "${SCRIPT_DIR}/${RESULT_DIR}/${task}/${basename}"
    fi
}

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUT_DIR="out"           # 임시/레거시 출력 (사용 안 함)
SOURCE_DIR="source"     # PDF에서 추출된 원문 MD 저장 위치
RESULT_DIR="result"     # 스킬 결과물(QnA/요약/덱 등) 카테고리별 저장 위치

TOTAL_START=$(date +%s)
OUTPUT_TARGET=""
[ -n "$NOTION_PAGE" ] && OUTPUT_TARGET="Notion"
[ "$MD_MODE" = "true" ] && OUTPUT_TARGET="${OUTPUT_TARGET:+${OUTPUT_TARGET} + }MD파일"
case "$TASK_TYPE" in
    summary) TASK_LABEL="요약" ;;
    convert) TASK_LABEL="원문 변환" ;;
    deck) TASK_LABEL="덱" ;;
    *) TASK_LABEL="QnA" ;;
esac
log "작업: ${TASK_LABEL} | 모드: ${SPLIT_MODE} ($([ "$SPLIT_MODE" = "split" ] && echo "챕터별 분리" || echo "단건 ${#PDF_PATHS[@]}개")) → ${OUTPUT_TARGET}"
log "파일: ${PDF_PATHS[*]}"
echo ""

# ===== Step 1: PDF 텍스트 추출 =====
STEP1_START=$(date +%s)
echo "[STEP:1:START] PDF 텍스트 추출"
mkdir -p "$SOURCE_DIR"
mkdir -p "$RESULT_DIR"

VALID_PDFS=()
IMAGE_PDFS=()

if [ "$FROM_MD" = "true" ]; then
    log "기존 MD 파일 사용 모드 - PDF 추출 스킵"
    for md in "${PDF_PATHS[@]}"; do
        if [ ! -f "$md" ]; then
            log "⚠️  파일 없음: $md"
            continue
        fi
        log "사용: $(basename "$md")"
        VALID_PDFS+=("$md")
    done
else
    for pdf in "${PDF_PATHS[@]}"; do
        log "추출 중: $(basename "$pdf")"
        RESULT=$(./gradlew run --args="'${pdf}' '${SOURCE_DIR}'" --quiet 2>&1 || true)
        echo "$RESULT"
        if echo "$RESULT" | grep -q "^SKIP:IMAGE_PDF:"; then
            log "이미지 기반 PDF 감지: $(basename "$pdf")"
            IMAGE_PDFS+=("$pdf")
        else
            VALID_PDFS+=("$pdf")
        fi
    done
fi

# 이미지 기반 PDF → Claude가 PDF를 직접 읽어 텍스트 추출
if [ ${#IMAGE_PDFS[@]} -gt 0 ]; then
    for pdf in "${IMAGE_PDFS[@]}"; do
        BASENAME="$(basename "$pdf" .pdf)"
        MD_PATH="${SCRIPT_DIR}/${SOURCE_DIR}/${BASENAME}.md"
        log "이미지 PDF Claude OCR 시작: ${BASENAME}"

        OCR_PROMPT="다음 PDF에서 모든 페이지의 텍스트를 추출해 하나의 마크다운 파일로 저장하세요.

[PDF 파일]
${pdf}

[출력 파일]
${MD_PATH}

규칙:
- Read 도구로 PDF 파일을 직접 읽으세요. 10페이지가 넘으면 pages 파라미터로 20페이지씩 분할해 모두 읽으세요 (예: pages: \"1-20\", pages: \"21-40\", ...). 마지막 청크가 비어 있을 때까지 반복합니다.
- 모든 페이지의 텍스트를 빠짐없이 추출하세요.
- 제목/소제목 구조를 마크다운 헤딩(#, ##, ###)으로 표현하세요.
- 본문은 원문 그대로 유지하세요.
- 표는 마크다운 테이블로 변환하세요.
- 코드는 코드 블록으로 감싸세요.
- 그림/다이어그램은 [그림: 설명] 형태로 표시하세요.
- 챕터/절 구분을 유지하세요."

        echo "$OCR_PROMPT" | claude -p --model sonnet --dangerously-skip-permissions

        if [ -f "$MD_PATH" ]; then
            log "  OCR 완료: ${BASENAME}"
            VALID_PDFS+=("$pdf")
        else
            log "  ⚠️  OCR 실패: ${BASENAME}"
        fi
    done
fi

if [ ${#VALID_PDFS[@]} -eq 0 ]; then
    log "처리할 수 있는 PDF가 없습니다."
    echo "[STEP:1:DONE] ($(elapsed "$STEP1_START"))"
    echo "[STEP:ALL:DONE] (총 $(elapsed "$TOTAL_START"))"
    exit 0
fi

PDF_PATHS=("${VALID_PDFS[@]}")

echo "[STEP:1:DONE] ($(elapsed "$STEP1_START"))"

# ===== Step 2: Q&A 생성 =====
STEP2_START=$(date +%s)

STEP2_LABEL="${TASK_LABEL} 생성"
[ -n "$NOTION_PAGE" ] && STEP2_LABEL="${STEP2_LABEL} + Notion 업로드"
[ "$MD_MODE" = "true" ] && STEP2_LABEL="${STEP2_LABEL} + MD 파일 생성"

# 스킬 이름 결정
case "$TASK_TYPE" in
    summary)
        SKILL_SPLIT="summary"
        SKILL_SINGLE="summary-single"
        SKILL_SPLIT_MD="summary-md"
        SKILL_SINGLE_MD="summary-single-md"
        ;;
    convert)
        SKILL_SPLIT="convert"
        SKILL_SINGLE="convert-single"
        SKILL_SPLIT_MD="convert-md"
        SKILL_SINGLE_MD="convert-single-md"
        ;;
    deck)
        SKILL_SPLIT="deck"
        SKILL_SINGLE="deck-single"
        SKILL_SPLIT_MD="deck-md"
        SKILL_SINGLE_MD="deck-single-md"
        ;;
    *)
        SKILL_SPLIT="qna"
        SKILL_SINGLE="qna-single"
        SKILL_SPLIT_MD="qna-md"
        SKILL_SINGLE_MD="qna-single-md"
        ;;
esac
echo "[STEP:2:START] ${STEP2_LABEL}"

PIDS=()

# --- Notion 업로드 ---
if [ -n "$NOTION_PAGE" ]; then
    if [ "$SPLIT_MODE" = "split" ]; then
        PDF_PATH="${PDF_PATHS[0]}"
        BASENAME="$(input_basename "$PDF_PATH")"
        EXTRACTED="$(extracted_path "$PDF_PATH")"

        log "챕터별 ${TASK_LABEL} 생성 + Notion 업로드 시작 (서브에이전트 병렬 방식)"
        (
            echo "/${SKILL_SPLIT}

[대상 파일]
${EXTRACTED}

[Notion 페이지]
${NOTION_PAGE}" | claude -p --model sonnet --dangerously-skip-permissions
        ) &
        PIDS+=($!)
    else
        for pdf in "${PDF_PATHS[@]}"; do
            BASENAME="$(input_basename "$pdf")"
            EXTRACTED="$(extracted_path "$pdf")"
            EXTRACTED_CONTENT="$(cat "$EXTRACTED")"
            (
                echo "/${SKILL_SINGLE}

[문서 내용]
${EXTRACTED_CONTENT}

[Notion 페이지]
${NOTION_PAGE}" | claude -p --model sonnet --dangerously-skip-permissions
            ) &
            PIDS+=($!)
            log "  Notion 시작: $(basename "$pdf")"
        done
    fi
fi

# --- MD 파일 생성 ---
if [ "$MD_MODE" = "true" ]; then
    if [ "$SPLIT_MODE" = "split" ]; then
        PDF_PATH="${PDF_PATHS[0]}"
        BASENAME="$(input_basename "$PDF_PATH")"
        EXTRACTED="$(extracted_path "$PDF_PATH")"
        if [ -n "$MD_DIR_CUSTOM" ]; then
            MD_OUT_DIR="$MD_DIR_CUSTOM"
        else
            CATEGORY="$(input_category "$PDF_PATH")"
            MD_OUT_DIR="$(result_dir_for "$BASENAME" "$TASK_TYPE" "$CATEGORY")"
        fi
        mkdir -p "$MD_OUT_DIR"

        log "챕터별 ${TASK_LABEL} MD 파일 생성 시작 (서브에이전트 병렬 방식)"
        log "출력 디렉토리: ${MD_OUT_DIR}"
        (
            echo "/${SKILL_SPLIT_MD}

[대상 파일]
${EXTRACTED}

[출력 디렉토리]
${MD_OUT_DIR}" | claude -p --model sonnet --dangerously-skip-permissions
        ) &
        PIDS+=($!)
    else
        for pdf in "${PDF_PATHS[@]}"; do
            BASENAME="$(input_basename "$pdf")"
            EXTRACTED="$(extracted_path "$pdf")"
            EXTRACTED_CONTENT="$(cat "$EXTRACTED")"
            if [ -n "$MD_DIR_CUSTOM" ]; then
                MD_OUT_DIR="$MD_DIR_CUSTOM"
            else
                CATEGORY="$(input_category "$pdf")"
                MD_OUT_DIR="$(result_dir_for "$BASENAME" "$TASK_TYPE" "$CATEGORY")"
            fi
            mkdir -p "$MD_OUT_DIR"
            case "$TASK_TYPE" in
                summary) MD_OUT_FILE="${MD_OUT_DIR}/${BASENAME}_summary.md" ;;
                convert) MD_OUT_FILE="${MD_OUT_DIR}/${BASENAME}.md" ;;
                deck) MD_OUT_FILE="${MD_OUT_DIR}/${BASENAME}_deck.json" ;;
                *) MD_OUT_FILE="${MD_OUT_DIR}/${BASENAME}_qna.md" ;;
            esac
            (
                echo "/${SKILL_SINGLE_MD}

[문서 내용]
${EXTRACTED_CONTENT}

[출력 파일]
${MD_OUT_FILE}" | claude -p --model sonnet --dangerously-skip-permissions
            ) &
            PIDS+=($!)
            log "  MD 시작: $(basename "$pdf") → ${MD_OUT_DIR}"
        done
    fi
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