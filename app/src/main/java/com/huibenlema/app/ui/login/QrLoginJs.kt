package com.huibenlema.app.ui.login

/**
 * 注入微信读书登录页的 JS 脚本常量。
 * 设计原则：JS 永不自行点击「刷新二维码」——只上报状态、执行 App 明确下发的动作，
 * 避免"自动刷新顶掉用户已确认的 ticket"（v2.0.0 教训：无状态自动点击导致登录成功仍超时）。
 * 唯一例外是自动点击「登录」入口：它只负责弹出二维码，不会作废已有 ticket。
 */
object QrLoginJs {

    /**
     * 状态上报脚本（幂等注入）：在 window.__hb 上挂 report()/clickRefresh()。
     * report() 返回 "gen,hasQr,expired,scanned" 逗号串：
     * - gen：二维码代数（点击刷新成功 +1，用于识别是否真的换码）
     * - hasQr：页面存在可见二维码容器或微信登录 iframe（兼容微信读书前端结构调整）
     * - expired：出现「二维码已失效/过期/点击刷新」文案（只扫叶子节点，避免父容器 textContent 拼接误判）
     * - scanned：出现「扫描成功/已扫描/请在手机上确认/确认登录」文案后置位并保持
     */
    val INIT: String = """
        (function() {
          if (window.__hb) return;
          var hb = {
            gen: 0,
            scanned: false,
            loginTries: 0,
            loginClicks: 0,
            lastLoginClickAt: 0
          };
          function shown(e) {
            var rects = e ? e.getClientRects() : null;
            return !!(e && (e.offsetWidth || e.offsetHeight || (rects && rects.length)));
          }
          // 只扫叶子节点：父容器的 textContent 会拼接所有后代文本，点/判都会误伤
          function leaf(re) {
            var els = document.querySelectorAll('div,span,a,p,button');
            for (var i = 0; i < els.length; i++) {
              var e = els[i];
              if (e.children.length > 0 || !shown(e)) continue;
              if (re.test((e.textContent || '').trim())) return true;
            }
            return false;
          }
          function hasQrNode() {
            var selectors = [
              '.login_qrcode_container',
              '.wxlogin-container',
              '.wr_login_modal_qr_wrapper',
              '.wr_login_modal_qr_wrapper_old'
            ];
            for (var i = 0; i < selectors.length; i++) {
              var nodes = document.querySelectorAll(selectors[i]);
              for (var j = 0; j < nodes.length; j++) {
                if (shown(nodes[j])) return true;
              }
            }
            var ifr = document.querySelectorAll('iframe');
            for (var i = 0; i < ifr.length; i++) {
              var src = ifr[i].src || '';
              if (shown(ifr[i]) && (
                src.indexOf('open.weixin.qq.com') >= 0 ||
                src.indexOf('login.weixin.qq.com') >= 0 ||
                src.indexOf('qrconnect') >= 0
              )) return true;
            }
            return false;
          }
          function normalize(e) {
            return ((e.textContent || e.getAttribute('aria-label') || e.getAttribute('title') || '')
              .replace(/\s+/g, ' ').trim());
          }
          function loginLeaf() {
            var re = /^(登录|立即登录|登录微信读书|扫码登录)$/;
            var els = document.querySelectorAll('a,button,div,span');
            for (var i = 0; i < els.length; i++) {
              var e = els[i];
              if (e.children.length > 0 || !shown(e)) continue;
              if (re.test(normalize(e))) return e;
            }
            return null;
          }
          function clickableFor(leafNode) {
            var e = leafNode;
            for (var i = 0; i < 4 && e; i++, e = e.parentElement) {
              var tag = (e.tagName || '').toLowerCase();
              var role = (e.getAttribute('role') || '').toLowerCase();
              if (tag == 'a' || tag == 'button' || role == 'button' ||
                  (e.style && e.style.cursor == 'pointer')) return e;
            }
            return leafNode;
          }
          function syntheticClick(e) {
            var opts = {bubbles: true, cancelable: true, view: window, button: 0, buttons: 1};
            ['pointerdown', 'mousedown', 'pointerup', 'mouseup'].forEach(function(type) {
              try {
                e.dispatchEvent(new MouseEvent(type, opts));
              } catch (_ignored) {}
            });
            e.click();
          }
          // 自动点「登录」弹出二维码（安全的唯一自动点击：只开弹窗、不刷新二维码）。
          // 守卫：二维码已弹立即停止；持续等待 SPA 慢渲染；点击后隔 3 秒仍无二维码才重试。
          // 最多 75 次轮询（约 60 秒），避免旧版 15 次（约 12 秒）在慢设备上提前放弃。
          hb.autoOpenLogin = function() {
            if (hb.loginTries >= 75 || hasQrNode()) return;
            hb.loginTries++;
            var now = Date.now();
            if (hb.lastLoginClickAt && now - hb.lastLoginClickAt < 3000) return;
            var leafNode = loginLeaf();
            if (!leafNode) return;
            syntheticClick(clickableFor(leafNode));
            hb.loginClicks++;
            hb.lastLoginClickAt = now;
            console.info('[HBLogin] auto_login_click count=' + hb.loginClicks);
          };
          hb.report = function() {
            var hasQr = hasQrNode();
            if (leaf(/扫描成功|已扫描|请在手机上确认|确认登录/)) hb.scanned = true;
            var expired = leaf(/二维码已失效|二维码过期|点击刷新二维码/);
            return hb.gen + ',' + (hasQr ? 1 : 0) + ',' + (expired ? 1 : 0) + ',' + (hb.scanned ? 1 : 0);
          };
          hb.clickRefresh = function() {
            var els = document.querySelectorAll('a,button,div,span');
            for (var i = 0; i < els.length; i++) {
              var e = els[i];
              if (e.children.length > 0 || !shown(e)) continue;
              var t = (e.textContent || '').trim();
              if (t === '点击刷新二维码' || t === '刷新二维码') {
                e.click(); hb.gen++; hb.scanned = false; return true;
              }
            }
            return false;
          };
          window.__hb = hb;
          // 页面渲染完成前重试，直到弹出二维码或达到上限
          setInterval(hb.autoOpenLogin, 800);
        })();
    """.trimIndent()

    /** 轮询状态：页面重载后 window.__hb 丢失，返回 "null"，调用方应重新注入 INIT */
    const val REPORT: String = "window.__hb ? window.__hb.report() : null"

    /** 点击页面刷新按钮（App 明确下发）；evaluateJavascript 回调为 "true"/"false" */
    const val CLICK_REFRESH: String = "window.__hb ? window.__hb.clickRefresh() : false"

    /** 桌面版 UA：确保展示网页版（含「登录」入口与扫码弹窗），手机 UA 会跳到无登录入口的手机版首页 */
    const val MOBILE_UA: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
}
