#!/data/data/com.termux/files/usr/bin/bash
# ─────────────────────────────────────────────────────────────────────────────
#  Clawdy · Termux 一键安装与诊断
#
#  在 Termux 里运行：
#     curl -fsSL https://raw.githubusercontent.com/QiGai77/clawdy/main/scripts/clawdy-setup.sh | bash
#  只做体检（不安装）：
#     curl -fsSL https://raw.githubusercontent.com/QiGai77/clawdy/main/scripts/clawdy-setup.sh | bash -s -- --diagnose
#  精简安装（不装 chromium / playwright）：
#     ... | bash -s -- --minimal
#
#  可以重复运行：已经装好的会跳过。
# ─────────────────────────────────────────────────────────────────────────────

MODE="full"
for a in "$@"; do
  case "$a" in
    --diagnose|-d) MODE="diagnose" ;;
    --minimal|-m)  MODE="minimal" ;;
    --help|-h)
      echo "用法: clawdy-setup.sh [--diagnose] [--minimal]"
      echo "  --diagnose  只体检，不安装任何东西"
      echo "  --minimal   跳过 chromium 和 playwright-core"
      exit 0 ;;
  esac
done

# ── colours ──────────────────────────────────────────────────────────────────
if [ -t 1 ] || [ -n "$FORCE_COLOR" ] || [ -n "$TERMUX_VERSION" ]; then
  R=$'\e[0m'; B=$'\e[1m'; DIM=$'\e[2m'
  RED=$'\e[31m'; GRN=$'\e[32m'; YEL=$'\e[33m'; BLU=$'\e[34m'; MAG=$'\e[35m'; CYN=$'\e[36m'
  CORAL=$'\e[38;5;209m'
else
  R=""; B=""; DIM=""; RED=""; GRN=""; YEL=""; BLU=""; MAG=""; CYN=""; CORAL=""
fi
OK="${GRN}✓${R}"; BAD="${RED}✗${R}"; WARN="${YEL}!${R}"; DOT="${CYN}•${R}"

STEP=0
step() { STEP=$((STEP+1)); printf "\n${CORAL}${B}[%d] %s${R}\n" "$STEP" "$1"; }
ok()   { printf "  %s %s\n" "$OK" "$1"; }
bad()  { printf "  %s %s\n" "$BAD" "$1"; }
warn() { printf "  %s %s\n" "$WARN" "$1"; }
info() { printf "  %s ${DIM}%s${R}\n" "$DOT" "$1"; }
have() { command -v "$1" >/dev/null 2>&1; }

banner() {
  printf "${CORAL}${B}"
  cat <<'ART'
   ________                   __
  / ____/ /___ __      ______/ /_  __
 / /   / / __ `/ | /| / / __  / / / /
/ /___/ / /_/ /| |/ |/ / /_/ / /_/ /
\____/_/\__,_/ |__/|__/\__,_/\__, /
                            /____/
ART
  printf "${R}${DIM}  Termux 一键安装与诊断 · 模式：%s${R}\n" "$MODE"
}

banner

# ── 0. is this the right Termux? ─────────────────────────────────────────────
step "检查 Termux"
PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
if [ ! -d "/data/data/com.termux/files/usr" ] || [ -z "$TERMUX_VERSION$PREFIX" ]; then
  bad "这里不是 Termux。请在手机上的 Termux 应用里运行这个脚本。"
  exit 1
fi
TV="${TERMUX_VERSION:-unknown}"
case "$TV" in
  googleplay*)
    bad "这是 Google Play 版 Termux（$TV）。"
    warn "Play 版不支持被其他应用调用（没有 RUN_COMMAND 权限），Clawdy 用不了它。"
    info "请卸载后从 F-Droid 或 GitHub 安装 Termux：https://f-droid.org/packages/com.termux/"
    exit 2 ;;
  unknown) warn "读不到 TERMUX_VERSION（Termux 版本较旧？）建议更新到 F-Droid / GitHub 最新版。" ;;
  *) ok "Termux $TV（F-Droid / GitHub 版）" ;;
esac
ARCH="$(uname -m)"; info "架构：$ARCH · Android $(getprop ro.build.version.release 2>/dev/null || echo '?')"

PROPS="$HOME/.termux/termux.properties"
LOGDIR="${TMPDIR:-$PREFIX/tmp}"; mkdir -p "$LOGDIR" 2>/dev/null

install_all() {
  # ── 1. allow-external-apps ────────────────────────────────────────────────
  step "允许 Clawdy 调用 Termux（allow-external-apps）"
  mkdir -p "$HOME/.termux"
  touch "$PROPS"
  if grep -Eq '^[[:space:]]*allow-external-apps[[:space:]]*=[[:space:]]*true' "$PROPS"; then
    ok "已经开启"
  else
    # drop any commented / false line, then add the real one
    sed -i -E '/^[#[:space:]]*allow-external-apps[[:space:]]*=.*/d' "$PROPS"
    echo "allow-external-apps=true" >> "$PROPS"
    ok "已写入 $PROPS"
  fi
  if have termux-reload-settings; then termux-reload-settings >/dev/null 2>&1 && ok "设置已重新加载" || warn "termux-reload-settings 失败，重启一次 Termux 也可以"; fi

  # ── 2. packages ───────────────────────────────────────────────────────────
  step "更新软件源（pkg update）"
  export DEBIAN_FRONTEND=noninteractive
  APT_OPTS='-o Dpkg::Options::=--force-confnew -o Dpkg::Options::=--force-confdef'
  if yes | pkg update -y $APT_OPTS >$LOGDIR/clawdy-pkg-update.log 2>&1 || yes | apt-get update >/dev/null 2>&1; then
    ok "软件源已更新"
  else
    warn "更新失败（网络或镜像问题）。可以运行 termux-change-repo 换一个镜像后重试。"
  fi

  step "安装基础工具"
  PKGS="git python nodejs-lts pandoc imagemagick poppler qpdf libxml2 libxslt curl"
  for p in $PKGS; do
    if dpkg -s "$p" >/dev/null 2>&1; then
      ok "$p 已安装"
    else
      printf "  ${DIM}… 安装 %s${R}\n" "$p"
      if yes | pkg install -y $APT_OPTS "$p" >$LOGDIR/clawdy-pkg-$p.log 2>&1; then ok "$p"; else bad "$p 安装失败（日志 $LOGDIR/clawdy-pkg-$p.log）"; fi
    fi
  done

  if [ "$MODE" != "minimal" ]; then
    step "安装 Chromium（网页渲染 / Playwright，可选）"
    if have chromium-browser || have chromium; then
      ok "chromium 已安装"
    else
      yes | pkg install -y x11-repo >/dev/null 2>&1
      yes | pkg install -y tur-repo >/dev/null 2>&1
      if yes | pkg install -y $APT_OPTS chromium >$LOGDIR/clawdy-pkg-chromium.log 2>&1; then ok "chromium"
      else warn "chromium 装不上（有的设备 / 镜像没有这个包），网页截图功能不可用，其他不受影响"; fi
    fi
  fi

  # ── 3. python libraries ───────────────────────────────────────────────────
  step "安装 Python 文档库"
  if have pip; then
    for m in python-docx python-pptx openpyxl markdown pypdf; do
      mod="$m"; case "$m" in python-docx) mod=docx ;; python-pptx) mod=pptx ;; esac
      if python -c "import $mod" >/dev/null 2>&1; then ok "$m 已安装"
      else
        printf "  ${DIM}… pip install %s${R}\n" "$m"
        if pip install --disable-pip-version-check -q "$m" >$LOGDIR/clawdy-pip-$m.log 2>&1; then ok "$m"; else bad "$m 安装失败（日志 $LOGDIR/clawdy-pip-$m.log）"; fi
      fi
    done
  else
    bad "没有 pip（python 没装好？）"
  fi

  # ── 4. node: playwright-core in ~/clawdy ──────────────────────────────────
  mkdir -p "$HOME/clawdy/default" "$HOME/clawdy/mcp"
  if [ "$MODE" != "minimal" ] && have npm; then
    step "安装 playwright-core（~/clawdy）"
    cd "$HOME/clawdy" || exit 1
    [ -f package.json ] || npm init -y >/dev/null 2>&1
    if [ -d node_modules/playwright-core ]; then ok "playwright-core 已安装"
    elif PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 npm install --no-fund --no-audit playwright-core >$LOGDIR/clawdy-npm.log 2>&1; then ok "playwright-core"
    else bad "playwright-core 安装失败（日志 $LOGDIR/clawdy-npm.log）"; fi
    cd - >/dev/null 2>&1
  fi

  # ── 5. storage ────────────────────────────────────────────────────────────
  step "共享存储（把结果交给你：手机存储/Clawdy）"
  if [ ! -d "$HOME/storage/shared" ]; then
    info "接下来会弹出「允许 Termux 访问文件」，请点允许"
    termux-setup-storage >/dev/null 2>&1
    for i in 1 2 3 4 5 6 7 8 9 10; do [ -d "$HOME/storage/shared" ] && break; sleep 1; done
  fi
  if [ -d "$HOME/storage/shared" ]; then
    mkdir -p "$HOME/storage/shared/Clawdy" && ok "~/storage/shared/Clawdy 就绪"
  else
    warn "还没有存储权限：之后在 Termux 里运行 termux-setup-storage"
  fi
  ok "工作区 ~/clawdy/default 就绪"
}

[ "$MODE" != "diagnose" ] && install_all

# ── DIAGNOSE ────────────────────────────────────────────────────────────────
step "体检"
ROWS=()
row() { ROWS+=("$1|$2|$3"); }   # status|name|detail
ver() { "$@" 2>&1 | head -n1 | tr -d '\r' | cut -c1-48; }

if grep -Eq '^[[:space:]]*allow-external-apps[[:space:]]*=[[:space:]]*true' "$PROPS" 2>/dev/null; then row ok "allow-external-apps" "true"; else row bad "allow-external-apps" "未开启（重新运行本脚本）"; fi
for t in git python node npm pandoc convert pdftotext qpdf; do
  if have "$t"; then
    case "$t" in
      git) d=$(ver git --version) ;; python) d=$(ver python --version) ;; node) d=$(ver node --version) ;;
      npm) d=$(ver npm --version) ;; pandoc) d=$(ver pandoc --version) ;; convert) d=$(ver convert -version | sed 's/Version: //') ;;
      pdftotext) d=$(pdftotext -v 2>&1 | head -n1) ;; qpdf) d=$(ver qpdf --version) ;;
    esac
    row ok "$t" "$d"
  else row bad "$t" "未安装"; fi
done
if have chromium-browser || have chromium; then row ok "chromium" "$(ver chromium-browser --version 2>/dev/null || ver chromium --version)"; else row warn "chromium" "未安装（可选）"; fi
if [ -d "$HOME/clawdy/node_modules/playwright-core" ]; then row ok "playwright-core" "~/clawdy"; else row warn "playwright-core" "未安装（可选）"; fi
for m in docx pptx openpyxl markdown pypdf; do
  if python -c "import $m" >/dev/null 2>&1; then row ok "py:$m" "可用"; else row bad "py:$m" "未安装"; fi
done
if [ -d "$HOME/storage/shared" ]; then row ok "共享存储" "~/storage/shared/Clawdy"; else row warn "共享存储" "运行 termux-setup-storage"; fi
FREE=$(df -h "$HOME" 2>/dev/null | awk 'NR==2{print $4}')
FREEK=$(df -k "$HOME" 2>/dev/null | awk 'NR==2{print $4}')
if [ -n "$FREEK" ] && [ "$FREEK" -lt 1048576 ] 2>/dev/null; then row warn "磁盘空间" "只剩 $FREE"; else row ok "磁盘空间" "可用 ${FREE:-?}"; fi
if have curl; then
  CODE=$(curl -s -o /dev/null -m 8 -w '%{http_code}' https://api.deepseek.com/ 2>/dev/null)
  if [ -n "$CODE" ] && [ "$CODE" != "000" ]; then row ok "网络" "api.deepseek.com → HTTP $CODE"; else row bad "网络" "连不上 api.deepseek.com"; fi
else row warn "网络" "没有 curl，跳过"; fi

PASS=0; FAIL=0; WARNS=0
printf "\n     ${B}项目                  详情${R}\n"
printf "  ${DIM}%s${R}\n" "──────────────────────────────────────────────────────"
for r in "${ROWS[@]}"; do
  s="${r%%|*}"; rest="${r#*|}"; n="${rest%%|*}"; d="${rest#*|}"
  case "$s" in
    ok) mark="$OK"; PASS=$((PASS+1)) ;;
    bad) mark="$BAD"; FAIL=$((FAIL+1)) ;;
    *) mark="$WARN"; WARNS=$((WARNS+1)) ;;
  esac
  w=$(printf %s "$n" | wc -L 2>/dev/null || echo ${#n}); pad=$((22 - w)); [ "$pad" -lt 1 ] && pad=1
  printf "  %s  %s%*s${DIM}%s${R}\n" "$mark" "$n" "$pad" "" "$d"
done
printf "  ${DIM}%s${R}\n" "──────────────────────────────────────────────────────"
printf "  ${GRN}${B}%d 通过${R}  ${YEL}${B}%d 提醒${R}  ${RED}${B}%d 失败${R}\n" "$PASS" "$WARNS" "$FAIL"

# ── next steps ──────────────────────────────────────────────────────────────
printf "\n${CORAL}${B}接下来${R}\n"
printf "  ${MAG}1.${R} 回到 Clawdy → 设置 → 工具连接 → Termux，点「授权」，允许「在 Termux 环境中运行命令」。\n"
printf "     ${DIM}没有弹窗：系统设置 → 应用 → Clawdy → 权限 → 其他权限。若 Termux 是在 Clawdy 之后装的，先把 Clawdy 覆盖安装一次。${R}\n"
printf "  ${MAG}2.${R} 系统设置 → 应用 → Termux → 电池：设为「不受限制 / 允许后台运行」，国产系统还要允许自启动。\n"
printf "  ${MAG}3.${R} 回到 Clawdy 点「测试连接」，看到「连接正常」就好了。\n"
if [ "$FAIL" -gt 0 ]; then
  printf "\n  ${YEL}有 %d 项失败：可以再运行一次本脚本；网络慢就先 termux-change-repo 换镜像。${R}\n" "$FAIL"
fi
printf "\n"
exit 0
