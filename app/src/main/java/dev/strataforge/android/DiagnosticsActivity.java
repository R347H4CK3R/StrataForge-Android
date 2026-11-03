package dev.strataforge.android;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.graphics.Color;
import android.widget.*;
import androidx.core.content.FileProvider;
import java.io.*;
import java.util.*;
import java.util.zip.*;

public class DiagnosticsActivity extends Activity {
  private LinearLayout root;
  private TextView contents;
  @Override public void onCreate(Bundle b){
    super.onCreate(b);
    ScrollView sc=new ScrollView(this);
    root=new LinearLayout(this);root.setOrientation(1);root.setPadding(18,18,18,18);
    root.setBackgroundColor(Color.rgb(13,18,27));sc.addView(root);setContentView(sc);
    TextView title=new TextView(this);title.setText("DIAGNOSTICS");title.setTextColor(Color.WHITE);title.setTextSize(25);root.addView(title);
    Button refresh=new Button(this);refresh.setText("Refresh logs");refresh.setOnClickListener(v->refresh());root.addView(refresh);
    Button export=new Button(this);export.setText("Export diagnostic ZIP");export.setOnClickListener(v->export());root.addView(export);
    TextView warning=new TextView(this);warning.setText("Logs may contain game paths, device details or account names. Review before sharing. No automatic uploads.");warning.setTextColor(Color.LTGRAY);root.addView(warning);
    contents=new TextView(this);contents.setTextColor(Color.WHITE);contents.setTextSize(13);contents.setPadding(0,16,0,0);root.addView(contents);
    refresh();
  }
  private void refresh(){
    File dir=Diagnostics.directory(this);File[] files=dir.listFiles();
    StringBuilder b=new StringBuilder();
    if(files==null||files.length==0)b.append("No diagnostic files yet.");
    else{
      Arrays.sort(files,(x,y)->Long.compare(y.lastModified(),x.lastModified()));
      for(File f:files)b.append(f.getName()).append(": ").append(f.length()).append(" bytes\n");
      File session=new File(dir,"session.log");
      if(session.exists())try(BufferedReader r=new BufferedReader(new FileReader(session))){
        ArrayDeque<String> tail=new ArrayDeque<>();String line;
        while((line=r.readLine())!=null){tail.addLast(line);if(tail.size()>60)tail.removeFirst();}
        b.append("\nRECENT EVENTS\n");
        for(String item:tail)b.append(item).append("\n");
      }catch(IOException e){b.append("Failed to read log.");}
    }
    contents.setText(b.toString());
  }
  private void export(){
    try{
      File dir=Diagnostics.directory(this);File[] files=dir.listFiles();
      if(files==null||files.length==0){Toast.makeText(this,"No diagnostic files",Toast.LENGTH_SHORT).show();return;}
      File outDir=new File(getCacheDir(),"updates");if(!outDir.exists())outDir.mkdirs();
      File target=new File(outDir,"strataforge-diagnostics.zip");
      try(ZipOutputStream z=new ZipOutputStream(new FileOutputStream(target))){
        byte[] buffer=new byte[8192];
        for(File f:files){
          if(!f.isFile()||f.length()>2*1024*1024)continue;
          z.putNextEntry(new ZipEntry(f.getName()));
          try(InputStream in=new FileInputStream(f)){int n;while((n=in.read(buffer))>0)z.write(buffer,0,n);}
          z.closeEntry();
        }
      }
      Uri uri=FileProvider.getUriForFile(this,getPackageName()+".updates",target);
      Intent share=new Intent(Intent.ACTION_SEND);share.setType("application/zip");
      share.putExtra(Intent.EXTRA_STREAM,uri);share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
      startActivity(Intent.createChooser(share,"Share StrataForge diagnostics"));
      Diagnostics.log("diagnostics","Exported diagnostics using Android Sharesheet");
    }catch(Exception e){
      Toast.makeText(this,"Export failed: "+e.getMessage(),Toast.LENGTH_LONG).show();
      Diagnostics.error("diagnostics",e);
    }
  }
}
