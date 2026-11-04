package dev.strataforge.android;
import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.graphics.Color;
import android.widget.*;
public final class GameExecutionActivity extends Activity {
  private LinearLayout root;
  private void label(String message){
    TextView t=new TextView(this);t.setText(message);t.setTextColor(Color.WHITE);
    t.setTextSize(15);t.setPadding(12,12,12,12);root.addView(t);
  }
  private void button(String text,Runnable cb){
    Button b=new Button(this);b.setText(text);b.setAllCaps(false);root.addView(b);
    b.setOnClickListener(v->cb.run());
  }
  @Override public void onCreate(Bundle b){
    super.onCreate(b);
    ScrollView scroll=new ScrollView(this);
    root=new LinearLayout(this);root.setOrientation(1);root.setPadding(18,16,18,16);root.setBackgroundColor(0xff101928);
    scroll.addView(root);setContentView(scroll);
    label("WINDOWS GAME EXECUTION — EXPERIMENTAL");
    label("Local game execution on this Android tablet is NOT verified. StrataForge stores Windows game files but does not itself include Wine, Box64 or DirectX translation.");
    label("Option 1: Winlator (local Windows compatibility). Requires separate setup, compatible game, enough memory and graphics support. Modern Call of Duty/anti-cheat titles are not validated.");
    button("Open Winlator",()->launch("com.winlator","Local Wine/Box64 runner not installed. Install a compatible official Winlator release first."));
    label("Option 2: Moonlight (fallback). Runs the Windows game on your PC and streams it to the tablet. Requires Moonlight plus a configured Sunshine host.");
    button("Open Moonlight",()->launch("com.limelight","Moonlight client not installed. Install Moonlight and configure Sunshine on the gaming laptop."));
    label("These are external app integrations. Launching Winlator or Moonlight does not mean an individual Windows game is running. No anti-cheat bypass is included.");
  }
  private void launch(String pkg,String unavailable){
    android.content.Intent intent=getPackageManager().getLaunchIntentForPackage(pkg);
    if(intent==null){
      new AlertDialog.Builder(this).setTitle("Runner unavailable").setMessage(unavailable)
        .setPositiveButton("OK",null).show();
      Diagnostics.log("execution","Runner not found: "+pkg);return;
    }
    try{startActivity(intent);Diagnostics.log("execution","Opened external runner "+pkg);}
    catch(Exception e){Diagnostics.error("execution",e);new AlertDialog.Builder(this).setMessage("Runner could not open: "+e.getMessage()).setPositiveButton("OK",null).show();}
  }
}
