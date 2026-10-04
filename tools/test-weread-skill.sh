#!/bin/bash
# 微信读书官方 Agent Gateway 实测脚本（阶段 0 接口样本采集）
#
# 第一次运行（全量接口，无需 bookId）:
#   WEREAD_API_KEY=wrk-你的key ./tools/test-weread-skill.sh
#
# 第二次运行（单本书详情/进度/章节价格）:
#   WEREAD_API_KEY=wrk-你的key BOOK_ID=某书ID ./tools/test-weread-skill.sh
#
# 产出: tools/samples/*.json
# 发送给我之前请先浏览一遍样本内容，删除你认为敏感的内容（如封面 URL、deepLink）。
set -euo pipefail

: "${WEREAD_API_KEY:?错误: 请先设置环境变量 WEREAD_API_KEY=wrk-...}"
GW="https://i.weread.qq.com/api/agent/gateway"
OUT="$(cd "$(dirname "$0")" && pwd)/samples"
SKILL_VER="1.0.4"
mkdir -p "$OUT"

call() {
  local name="$1"; local body="$2"
  echo "==> $name"
  curl -sS --connect-timeout 10 -m 30 -X POST "$GW" \
    -H "Authorization: Bearer $WEREAD_API_KEY" \
    -H "Content-Type: application/json" \
    -d "$body" -o "$OUT/$name.json"
  echo "  已保存 $(wc -c < "$OUT/$name.json" | tr -d ' ') 字节"
  echo
}

echo "########## 全量接口 ##########"
# 1. 所有可用接口一览（最重要：一次看清官方开放了什么，含参数定义）
call 01_api_list '{"api_name":"/_list","skill_version":"1.0.4"}'
# 2. 书架
call 02_shelf_sync '{"api_name":"/shelf/sync","skill_version":"1.0.4"}'
# 3. 阅读统计：本月 / 总计（readTimes 按天分桶 → 每日价值数据源）
call 03_read_monthly '{"api_name":"/readdata/detail","mode":"monthly","skill_version":"1.0.4"}'
call 04_read_overall '{"api_name":"/readdata/detail","mode":"overall","skill_version":"1.0.4"}'
# 5. 笔记书籍清单（官方 gateway 版，重点看是否含价格字段）
call 05_notebooks '{"api_name":"/user/notebooks","count":100,"skill_version":"1.0.4"}'

if [ -n "${BOOK_ID:-}" ]; then
  echo "########## 单本书: BOOK_ID=$BOOK_ID ##########"
  call 10_book_info "{\"api_name\":\"/book/info\",\"bookId\":\"$BOOK_ID\",\"skill_version\":\"$SKILL_VER\"}"
  call 11_getprogress "{\"api_name\":\"/book/getprogress\",\"bookId\":\"$BOOK_ID\",\"skill_version\":\"$SKILL_VER\"}"
  call 12_chapterinfo "{\"api_name\":\"/book/chapterinfo\",\"bookId\":\"$BOOK_ID\",\"skill_version\":\"$SKILL_VER\"}"
else
  echo "提示: 如需单本书详情/进度/章节价格，从 02_shelf_sync.json 里挑一个 bookId，运行:"
  echo "  WEREAD_API_KEY=wrk-你的key BOOK_ID=书ID $0"
fi

echo "完成！样本保存在 $OUT/ ，请检查内容后发给我。"
