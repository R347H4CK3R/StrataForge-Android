package dev.strataforge.android;
import android.content.*;
import android.os.*;
import java.io.*;
import java.util.*;

final class GameInstallStore {
  private static final String ROOT="steamapps";
  static File root(Context c){return new File(c.getExternalFilesDir(null),ROOT);}
  static File depotConfig(Context c){return new File(root(c),".DepotDownloader/depot.config");}
  static long directorySize(File f){
    if(f==null||!f.exists())return 0;
    if(f.isFile())return f.length();
    long size=0;File[] files=f.listFiles();
    if(files!=null)for(File item:files)size+=directorySize(item);
    return size;
  }
  static int countFiles(File root){
    if(root==null||!root.exists())return 0;
    if(root.isFile())return 1;
    int count=0;File[] entries=root.listFiles();
    if(entries!=null)for(File entry:entries)count+=countFiles(entry);
    return count;
  }
  static String size(long b){return String.format(Locale.US,"%.1f MiB",b/1048576d);}
  static File findGame(Context c,int appId){
    File top=root(c);File[] games=top.listFiles(File::isDirectory);
    if(games==null)return null;
    for(File game:games){
      if(game.getName().startsWith("."))continue;
      if(new File(game,"installscript_"+appId+".vdf").exists())return game;
    }
    // Steam sometimes uses a folder with no installscript. Avoid guessing.
    return null;
  }
  static boolean completed(Context c,int id){
    return c.getSharedPreferences("verified_steam_downloads",0).getBoolean(String.valueOf(id),false);
  }
  static void completed(Context c,int id,boolean success){
    c.getSharedPreferences("verified_steam_downloads",0).edit().putBoolean(String.valueOf(id),success).apply();
  }
  static String status(Context c,int id){
    File f=findGame(c,id);
    if(f==null)return "Not downloaded";
    int files=countFiles(f);
    long size=directorySize(f);
    String state=completed(c,id)?"Downloaded (completion recorded)":"Partial / needs verification";
    return state+" • "+files+" files • "+size(size);
  }
  static String verify(Context c,int id){
    File f=findGame(c,id);
    if(f==null)return "No matching downloaded game folder. Download/repair will use existing files.";
    int files=countFiles(f);long bytes=directorySize(f);
    Diagnostics.log("game-"+id,"Local verification: "+files+" files; "+size(bytes)+"; completion="+completed(c,id));
    if(files==0||bytes==0)return "Game folder is empty";
    if(!completed(c,id))return "Files found ("+size(bytes)+"); Steam depot completion not recorded. Tap Repair to redownload missing chunks.";
    return "Downloaded: "+files+" files, "+size(bytes)+". Manifest hash integrity is not yet verified. Tap Repair to recheck with Steam.";
  }
}
