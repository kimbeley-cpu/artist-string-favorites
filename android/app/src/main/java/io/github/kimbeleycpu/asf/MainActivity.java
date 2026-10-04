package io.github.kimbeleycpu.asf;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.OutputStream;

/** 把网页版包成一个安卓应用：始终加载线上最新的页面，数据存在应用自己的空间里。 */
public class MainActivity extends Activity {
    private static final String HOME = "https://kimbeley-cpu.github.io/artist-string-favorites/";
    private static final String HOST = "kimbeley-cpu.github.io";
    private static final int PICK = 1001;

    private WebView web;
    private ValueCallback<Uri[]> fileCb;
    private OutputStream out;
    private Uri outUri;

    // 网页里用 <a download> 保存文件时，WebView 不会处理 blob: 地址，这段脚本把内容分块交给应用来写入「下载」文件夹
    private static final String HOOK =
        "(function(){if(window.__asfHook)return;window.__asfHook=1;"
      + "var oc=HTMLAnchorElement.prototype.click;"
      + "HTMLAnchorElement.prototype.click=function(){"
      + "var a=this;if(a.hasAttribute('download')&&/^(blob:|data:)/.test(a.href)){"
      + "fetch(a.href).then(function(r){return r.blob()}).then(function(b){"
      + "if(!AsfApp.begin(a.download||'download',b.type||'application/octet-stream'))return;"
      + "var p=0,S=524288;(function n(){if(p>=b.size){AsfApp.end();return}"
      + "var fr=new FileReader();fr.onload=function(){var s=fr.result;AsfApp.chunk(s.slice(s.indexOf(',')+1));p+=S;n()};"
      + "fr.readAsDataURL(b.slice(p,p+S))})()}).catch(function(){AsfApp.fail()});return}"
      + "return oc.apply(this,arguments)}})();";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        web = new WebView(this);
        web.setBackgroundColor(0xFF17162A);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setSupportZoom(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setUserAgentString(s.getUserAgentString() + " AsfApp/1");

        web.addJavascriptInterface(new Bridge(), "AsfApp");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (HOST.equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception e) { /* 没有可以打开的应用 */ }
                return true;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                v.evaluateJavascript(HOOK, null);
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                if (p.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try {
                    startActivityForResult(Intent.createChooser(i, "选择文件"), PICK);
                } catch (Exception e) {
                    fileCb = null;
                    cb.onReceiveValue(null);
                }
                return true;
            }
        });

        if (state == null) web.loadUrl(HOME); else web.restoreState(state);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PICK || fileCb == null) return;
        Uri[] r = null;
        if (res == RESULT_OK && data != null) {
            ClipData cd = data.getClipData();
            if (cd != null && cd.getItemCount() > 0) {
                r = new Uri[cd.getItemCount()];
                for (int i = 0; i < r.length; i++) r[i] = cd.getItemAt(i).getUri();
            } else if (data.getData() != null) {
                r = new Uri[]{data.getData()};
            }
        }
        fileCb.onReceiveValue(r);
        fileCb = null;
    }

    /** 返回键：先关掉网页里打开的弹窗，没有弹窗时才退到后台。 */
    @Override
    public void onBackPressed() {
        web.evaluateJavascript(
            "(function(){var d=document.querySelectorAll('dialog[open]');if(d.length){d[d.length-1].close();return 1}return 0})()",
            v -> { if (!"1".equals(v)) moveTaskToBack(true); });
    }

    private void tip(final String t) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, t, Toast.LENGTH_SHORT).show());
    }

    private class Bridge {
        @JavascriptInterface
        public synchronized boolean begin(String name, String mime) {
            try {
                close(false);
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                outUri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (outUri == null) throw new Exception("no uri");
                out = getContentResolver().openOutputStream(outUri);
                return out != null;
            } catch (Exception e) {
                tip("保存失败：" + e.getMessage());
                return false;
            }
        }

        @JavascriptInterface
        public synchronized void chunk(String b64) {
            try { if (out != null) out.write(Base64.decode(b64, Base64.DEFAULT)); }
            catch (Exception e) { close(true); tip("保存失败：" + e.getMessage()); }
        }

        @JavascriptInterface
        public synchronized void end() {
            if (out == null) return;
            close(false);
            tip("已保存到「下载」文件夹");
        }

        @JavascriptInterface
        public synchronized void fail() {
            close(true);
            tip("保存失败");
        }

        private void close(boolean discard) {
            try { if (out != null) out.close(); } catch (Exception e) { /* ignore */ }
            if (discard && outUri != null) {
                try { getContentResolver().delete(outUri, null, null); } catch (Exception e) { /* ignore */ }
            }
            out = null;
            outUri = null;
        }
    }
}
