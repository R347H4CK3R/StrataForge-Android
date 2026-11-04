package dev.strataforge.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.graphics.Color;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.Locale;

public final class GameExecutionActivity extends Activity {
  private static final String WINLATOR="com.winlator";
  private static final String MOONLIGHT="com.limelight";
  private static final String WINLATOR_URL="https://github.com/brunodev85/winlator/releases";
  private static final String MOONLIGHT_URL="https://github.com/moonlight-stream/moonlight-android/releases";
  private static final String SUNSHINE_URL="https://github.com/LizardByte/Sunshine/releases";
  private LinearLayout root;
  private TextView winStatus,moonStatus;

  private void label(String content) {
    TextView text=new TextView(this);
    text.setText(content);text.setTextColor(Color.WHITE);text.setTextSize(15);
    text.setPadding(12,12,12,12);root.addView(text);
  }
  private void button(String name,Runnable action){
    Button button=new Button(this);button.setAllCaps(false);button.setText(name);
    button.setOnClickListener(v->action.run());root.addView(button);
  }
  private TextView status(){
    TextView text=new TextView(this);text.setTextSize(15);
    text.setPadding(12,10,12,10);root.addView(text);return text;
  }
  private boolean available(String pkg){
    return getPackageManager().getLaunchIntentForPackage(pkg)!=null;
  }
  private void updateStatus(){
    boolean win=available(WINLATOR),moon=available(MOONLIGHT);
    winStatus.setText("Winlator: "+(win?"Installed; local compatibility not verified":"Not installed"));
    winStatus.setTextColor(win?0xff9ee8b6:0xffffcc80);
    moonStatus.setText("Moonlight: "+(moon?"Installed; Sunshine pairing not verified":"Not installed"));
    moonStatus.setTextColor(moon?0xff9ee8b6:0xffffcc80);
    Diagnostics.log("execution","Runner detection: Winlator="+win+", Moonlight="+moon);
  }
  @Override protected void onResume(){super.onResume();if(winStatus!=null)updateStatus();}
  @Override public void onCreate(Bundle saved){
    super.onCreate(saved);
    ScrollView scroll=new ScrollView(this);
    root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(18,16,18,16);root.setBackgroundColor(0xff101928);
    scroll.addView(root);setContentView(scroll);
    label("WINDOWS GAME EXECUTION | LOCAL + STREAMING");
    label("StrataForge manages downloaded Windows game files. External runners are separate apps, not bundled. Modern Call of Duty cannot be claimed playable on this tablet.");
    winStatus=status();
    label("OPTION 1: WINLATOR | Local Wine/Box64 runner");
    label("Open Winlator and configure its container. On your Mali GPU try the compatible VirGL path; 3D game compatibility and performance are limited.");
    button("Open Winlator / Set up",()->launchOrSetup(WINLATOR,WINLATOR_URL,"Winlator"));
    button("Winlator official releases",()->openUrl(WINLATOR_URL));
    moonStatus=status();
    label("OPTION 2: MOONLIGHT | PC streaming fallback");
    label("Install Sunshine on your Windows laptop, enable a game/desktop stream, then pair Moonlight with Sunshine using the PIN shown in Moonlight. The PC runs the game.");
    button("Open Moonlight / Set up",()->launchOrSetup(MOONLIGHT,MOONLIGHT_URL,"Moonlight"));
    button("Download Moonlight for Android",()->openUrl(MOONLIGHT_URL));
    button("Sunshine host downloads (Windows laptop)",()->openUrl(SUNSHINE_URL));
    button("Open preferred runner (fallback automatically)",()->preferred());
    button("Check installed runners",this::updateStatus);
    label("Fallback opens Moonlight only when Winlator is missing or cannot be launched. It cannot automatically transfer or execute Windows game files; Moonlight streams the PC's game installation.");
    label("Runtime checks cover presence and launchability, not game execution success. No anti-cheat bypass or unsupported compatibility claims.");
    updateStatus();
  }
  private void preferred(){
    if(available(WINLATOR)){
      new AlertDialog.Builder(this).setTitle("Choose execution route")
        .setMessage("Try local Winlator first? Moonlight streams from your PC and is usually more suitable for demanding Windows games.")
        .setPositiveButton("Winlator",(dialog,which)->launchOrFallback())
        .setNeutralButton("Moonlight",(dialog,which)->launchOrSetup(MOONLIGHT,MOONLIGHT_URL,"Moonlight"))
        .setNegativeButton("Cancel",null).show();
    }else launchOrSetup(MOONLIGHT,MOONLIGHT_URL,"Moonlight");
  }
  private void launchOrFallback(){
    if(!openApp(WINLATOR))launchOrSetup(MOONLIGHT,MOONLIGHT_URL,"Moonlight");
  }
  private void launchOrSetup(String pkg,String url,String name){
    if(openApp(pkg))return;
    new AlertDialog.Builder(this).setTitle(name+" unavailable")
      .setMessage(name+" isn't installed or couldn't be opened. Open its official release page to install it manually?")
      .setPositiveButton("Open downloads",(dialog,which)->openUrl(url))
      .setNegativeButton("Cancel",null).show();
  }
  private boolean openApp(String pkg){
    try{
      Intent intent=getPackageManager().getLaunchIntentForPackage(pkg);
      if(intent==null)return false;
      startActivity(intent);
      Diagnostics.log("execution","Opened external runtime "+pkg);
      return true;
    }catch(Exception error){
      Diagnostics.error("execution",error);
      return false;
    }
  }
  private void openUrl(String url){
    try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}
    catch(Exception e){
      Diagnostics.error("execution",e);
      new AlertDialog.Builder(this).setTitle("Unable to open link").setMessage(url)
        .setPositiveButton("OK",null).show();
    }
  }
}
