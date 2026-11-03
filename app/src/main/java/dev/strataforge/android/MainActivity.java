package dev.strataforge.android;
import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.view.*;
import android.widget.*;
import java.util.*;

public class MainActivity extends Activity {
  private static final int PICK=3107;
  private static final int BG=Color.rgb(11,15,24), SURFACE=Color.rgb(27,34,47), ACCENT=Color.rgb(96,157,249);
  private LinearLayout layout,library;
  private EditText search;
  private String filter="";
  private boolean cards=true;
  @Override public void onCreate(Bundle b){super.onCreate(b);render();}
  private TextView text(String value,int size,int color,boolean bold){
    TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);
    if(bold)t.setTypeface(null,Typeface.BOLD);return t;
  }
  private LinearLayout vertical(){LinearLayout v=new LinearLayout(this);v.setOrientation(1);return v;}
  private GradientDrawable rounded(int color,int radius){
    GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(radius);return g;
  }
  private Button button(String label,Runnable cb){
    Button b=new Button(this);b.setAllCaps(false);b.setText(label);b.setOnClickListener(v->cb.run());return b;
  }
  private void open(Class<?> activity){startActivity(new Intent(this,activity));}
  private void render(){
    LinearLayout shell=vertical();shell.setBackgroundColor(BG);setContentView(shell);
    ScrollView scroll=new ScrollView(this);shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
    layout=vertical();layout.setPadding(18,14,18,14);scroll.addView(layout);
    LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);
    TextView heading=text("STRATAFORGE",26,Color.WHITE,true);top.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
    TextView version=text("ANDROID",12,ACCENT,true);top.addView(version);layout.addView(top);
    TextView subtitle=text("Your games, downloads and settings",14,Color.LTGRAY,false);subtitle.setPadding(0,3,0,22);layout.addView(subtitle);
    LinearLayout featured=vertical();featured.setPadding(22,22,22,22);featured.setBackground(rounded(SURFACE,24));
    featured.addView(text("STEAM LIBRARY",14,ACCENT,true));
    featured.addView(text("Explore your games",25,Color.WHITE,true));
    TextView desc=text("Connect to Steam, browse owned games and manage downloads.",14,Color.LTGRAY,false);
    desc.setPadding(0,8,0,8);featured.addView(desc);
    featured.addView(button("Open Steam library",()->open(SteamProtocolActivity.class)));
    layout.addView(featured);
    TextView section=text("LIBRARY",21,Color.WHITE,true);section.setPadding(0,23,0,8);layout.addView(section);
    LinearLayout tools=new LinearLayout(this);tools.setOrientation(0);
    search=new EditText(this);search.setSingleLine(true);search.setHint("Search games");search.setTextColor(Color.WHITE);
    search.setHintTextColor(Color.GRAY);search.setText(filter);tools.addView(search,new LinearLayout.LayoutParams(0,-2,1));
    tools.addView(button(cards?"List":"Cards",()->{filter=search.getText().toString();cards=!cards;render();}));
    layout.addView(tools);
    search.addTextChangedListener(new android.text.TextWatcher(){
      public void beforeTextChanged(CharSequence s,int a,int c,int f){}
      public void onTextChanged(CharSequence s,int start,int before,int count){filter=s.toString();showImported();}
      public void afterTextChanged(android.text.Editable e){}
    });
    TextView steamSection=text("Steam  •  Owned games",17,Color.WHITE,true);
    steamSection.setPadding(0,14,0,6);layout.addView(steamSection);
    layout.addView(button("View installed / not installed Steam games",()->open(SteamProtocolActivity.class)));
    LinearLayout otherHead=new LinearLayout(this);otherHead.setGravity(Gravity.CENTER_VERTICAL);
    otherHead.addView(text("OTHER GAMES",17,Color.WHITE,true),new LinearLayout.LayoutParams(0,-2,1));
    otherHead.addView(button("+ Add",()->choose()));otherHead.setPadding(0,14,0,6);layout.addView(otherHead);
    library=vertical();layout.addView(library);showImported();
    LinearLayout nav=new LinearLayout(this);nav.setBackgroundColor(SURFACE);
    String[] names={"Library","Steam","Downloads","Settings"};
    for(int i=0;i<names.length;i++){final int idx=i;nav.addView(button(names[i],()->{
      if(idx==0)render();else if(idx==1||idx==2)open(SteamProtocolActivity.class);else settings();
    }),new LinearLayout.LayoutParams(0,60,1));}
    shell.addView(nav);
    Diagnostics.log("ui","Opened Madeira-style library");
  }
  private void showImported(){
    if(library==null)return;library.removeAllViews();
    Set<String> all=getSharedPreferences("strataforge_files",0).getStringSet("files",Collections.emptySet());
    if(all.isEmpty()){TextView empty=text("No local games added yet. Import a file to begin.",14,Color.LTGRAY,false);empty.setPadding(8,20,8,20);library.addView(empty);}
    for(String entry:new TreeSet<>(all)){
      String title=entry.split("\\|",2)[0];if(!title.toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT)))continue;
      LinearLayout tile=new LinearLayout(this);tile.setGravity(Gravity.CENTER_VERTICAL);tile.setPadding(15,16,15,16);tile.setBackground(rounded(SURFACE,18));
      LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,5,0,5);library.addView(tile,lp);
      TextView name=text(title,cards?18:15,Color.WHITE,true);tile.addView(name,new LinearLayout.LayoutParams(0,-2,1));
      tile.addView(button("Details",()->detail(title,entry)));
    }
  }
  private void detail(String name,String entry){
    new AlertDialog.Builder(this).setTitle(name)
      .setMessage("Local file imported. Game execution is not yet supported. Diagnostic records remain available in Settings.")
      .setPositiveButton("OK",null).setNeutralButton("Remove",(a,b)->{
        Set<String> all=new HashSet<>(getSharedPreferences("strataforge_files",0).getStringSet("files",Collections.emptySet()));
        all.remove(entry);getSharedPreferences("strataforge_files",0).edit().putStringSet("files",all).apply();
        Diagnostics.log("library","Removed local library entry "+name);showImported();
      }).show();
  }
  private void settings(){
    String[] options={"Check for updates","Diagnostics and crash logs","Device information","Steam website"};
    new AlertDialog.Builder(this).setTitle("Settings").setItems(options,(d,index)->{
      if(index==0)open(UpdaterActivity.class);
      if(index==1)open(DiagnosticsActivity.class);
      if(index==2)device();
      if(index==3)open(SteamActivity.class);
    }).show();
  }
  private void device(){
    android.app.ActivityManager am=(android.app.ActivityManager)getSystemService(ACTIVITY_SERVICE);
    android.app.ActivityManager.MemoryInfo memory=new android.app.ActivityManager.MemoryInfo();am.getMemoryInfo(memory);
    new AlertDialog.Builder(this).setTitle("Device diagnostics")
      .setMessage(Build.MANUFACTURER+" "+Build.MODEL+"\nAndroid "+Build.VERSION.RELEASE+
      "\nABI "+Build.SUPPORTED_ABIS[0]+"\nRAM "+(memory.totalMem/1048576)+" MiB"+
      "\nWindows game runtime: not integrated")
      .setPositiveButton("OK",null).show();
  }
  private void choose(){
    Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);
    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(i,PICK);
  }
  @Override protected void onActivityResult(int request,int result,Intent data){
    super.onActivityResult(request,result,data);
    if(request!=PICK||result!=RESULT_OK||data==null||data.getData()==null)return;
    Uri uri=data.getData();
    try{
      getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);
      String title=uri.getLastPathSegment();
      try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
        if(c!=null&&c.moveToFirst())title=c.getString(0);
      }
      if(title==null)title="Game file";
      Set<String> files=new HashSet<>(getSharedPreferences("strataforge_files",0).getStringSet("files",Collections.emptySet()));
      files.add(title+"|"+uri);
      getSharedPreferences("strataforge_files",0).edit().putStringSet("files",files).apply();
      Diagnostics.log("library","Imported local file "+title);showImported();
    }catch(Exception e){Diagnostics.error("import",e);Toast.makeText(this,"Import error: "+e.getMessage(),Toast.LENGTH_LONG).show();}
  }
}
