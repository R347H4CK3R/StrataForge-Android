package dev.strataforge.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

public class SteamActivity extends Activity {
    private static final String STORE="https://store.steampowered.com/";
    private static final String LIBRARY="https://steamcommunity.com/my/games/?tab=all";
    private static final String LOGIN="https://steamcommunity.com/login/home/";
    private WebView browser;
    private ProgressBar progress;

    private boolean trusted(Uri uri) {
        String host=uri.getHost();
        if(host==null || !"https".equalsIgnoreCase(uri.getScheme()))return false;
        host=host.toLowerCase(java.util.Locale.ROOT);
        return host.equals("steampowered.com") || host.endsWith(".steampowered.com")
             || host.equals("steamcommunity.com") || host.endsWith(".steamcommunity.com")
             || host.equals("steamstatic.com") || host.endsWith(".steamstatic.com");
    }
    private Button action(String title, View.OnClickListener click) {
        Button button=new Button(this);
        button.setText(title);button.setAllCaps(false);button.setOnClickListener(click);
        return button;
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(13,18,27));
        setContentView(root);
        TextView title=new TextView(this);
        title.setText("STEAM  •  STRATAFORGE");
        title.setTextColor(Color.WHITE);title.setTextSize(21);title.setPadding(16,12,16,8);
        root.addView(title);
        TextView warning=new TextView(this);
        warning.setText("Steam website sign-in only. Native depot downloading is NOT implemented.");
        warning.setTextColor(Color.LTGRAY);warning.setTextSize(13);warning.setPadding(12,4,12,8);
        root.addView(warning);
        LinearLayout nav=new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(nav);
        nav.addView(action("Sign in",v->browser.loadUrl(LOGIN)),new LinearLayout.LayoutParams(0,-2,1));
        nav.addView(action("My games",v->browser.loadUrl(LIBRARY)),new LinearLayout.LayoutParams(0,-2,1));
        nav.addView(action("Store",v->browser.loadUrl(STORE)),new LinearLayout.LayoutParams(0,-2,1));
        nav.addView(action("Sign out",v->signOut()),new LinearLayout.LayoutParams(0,-2,1));
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);root.addView(progress,new LinearLayout.LayoutParams(-1,4));
        browser=new WebView(this);
        root.addView(browser,new LinearLayout.LayoutParams(-1,0,1));
        WebSettings settings=browser.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setSafeBrowsingEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(browser,true);
        browser.setWebChromeClient(new WebChromeClient(){
            @Override public void onProgressChanged(WebView view,int amount){progress.setProgress(amount);}
        });
        browser.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){
                Uri uri=request.getUrl();
                if(trusted(uri))return false;
                if(request.isForMainFrame() && ("https".equalsIgnoreCase(uri.getScheme()))){
                    startActivity(new Intent(Intent.ACTION_VIEW,uri));
                }
                return true;
            }
        });
        browser.loadUrl(STORE);
    }
    private void signOut(){
        new AlertDialog.Builder(this).setTitle("Sign out of Steam")
            .setMessage("This clears Steam website cookies and local website data in StrataForge. Other apps are unaffected.")
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Sign out",(dialog,which)->{
                browser.clearCache(true);
                browser.clearHistory();
                browser.clearFormData();
                browser.clearMatches();
                android.webkit.WebStorage.getInstance().deleteAllData();
                CookieManager.getInstance().removeAllCookies(v->browser.loadUrl(STORE));
                CookieManager.getInstance().flush();
            }).show();
    }
    @Override public void onBackPressed(){
        if(browser!=null&&browser.canGoBack())browser.goBack(); else super.onBackPressed();
    }
    @Override protected void onDestroy(){
        if(browser!=null){browser.destroy();browser=null;}
        super.onDestroy();
    }
}
