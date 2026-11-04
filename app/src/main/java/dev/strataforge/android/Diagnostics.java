package dev.strataforge.android;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.util.Log;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public final class Diagnostics extends Application {
  private static final Object LOCK=new Object();
  private static final long MAX_LOG=512*1024;
  private static final int MAX_CRASHES=8;
  private static Context context;
  private static Thread.UncaughtExceptionHandler previous;
  @Override public void onCreate(){
    super.onCreate();
    context=getApplicationContext();
    previous=Thread.getDefaultUncaughtExceptionHandler();
    Thread.setDefaultUncaughtExceptionHandler((thread,error)->{
      try{
        StringWriter sw=new StringWriter();
        error.printStackTrace(new PrintWriter(sw));
        write("FATAL",thread.getName(),sw.toString());
        writeCrash(sw.toString());
      }catch(Throwable ignored){}
      if(previous!=null)previous.uncaughtException(thread,error);
      else android.os.Process.killProcess(android.os.Process.myPid());
    });
    log("app","Process started - Android "+Build.VERSION.RELEASE+" / "+Build.SUPPORTED_ABIS[0]);
    scheduleUpload(this);
    new Thread(()->GithubDiagnostics.uploadPending(getApplicationContext()),"initial-diagnostics-upload").start();
  }
  static void scheduleUpload(Context c){
    try{
      JobScheduler scheduler=(JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
      if(scheduler!=null){
        JobInfo job=new JobInfo.Builder(11042,new ComponentName(c,DiagnosticUploadJob.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .setPeriodic(15*60*1000L)
            .build();
        scheduler.schedule(job);
      }
    }catch(Exception ignored){}
  }
  private static String now(){
    SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.US);
    f.setTimeZone(TimeZone.getTimeZone("UTC"));
    return f.format(new Date());
  }
  static File directory(Context c){File f=new File(c.getFilesDir(),"diagnostics");if(!f.exists())f.mkdirs();return f;}
  private static String strip(String message){
    if(message==null)return "";
    // Avoid persisting QR challenges and common credential/token parameters.
    String s=message.replaceAll("(?i)(access_token|refresh_token|password|steamLoginSecure|sessionid|token|authcode)=([^&\\s]+)","$1=[REDACTED]");
    return s.length()>8000?s.substring(0,8000)+" [truncated]":s;
  }
  static void log(String category,String message){write("INFO",category,message);}
  static void error(String category,Throwable e){write("ERROR",category,e.getClass().getSimpleName()+": "+e.getMessage());}
  static void write(String level,String category,String message){
    Context c=context;if(c==null)return;
    synchronized(LOCK){
      try{
        File f=new File(directory(c),"session.log");
        if(f.length()>MAX_LOG){
          File old=new File(directory(c),"session.previous.log");
          if(old.exists())old.delete();
          f.renameTo(old);
        }
        try(FileWriter out=new FileWriter(f,true)){
          out.write(now()+" ["+level+"] ["+strip(category)+"] "+strip(message).replace("\n"," | ")+"\n");
        }
      }catch(IOException ex){Log.e("StrataForge","Diagnostic write failed",ex);}
    }
  }
  private static void writeCrash(String trace){
    Context c=context;if(c==null)return;
    synchronized(LOCK){
      try{
        File dir=directory(c);
        File[] items=dir.listFiles((parent,name)->name.startsWith("crash-")&&name.endsWith(".txt"));
        if(items!=null&&items.length>=MAX_CRASHES){
          java.util.Arrays.sort(items,(a,b)->Long.compare(a.lastModified(),b.lastModified()));
          for(int i=0;i<=items.length-MAX_CRASHES;i++)items[i].delete();
        }
        File f=new File(dir,"crash-"+System.currentTimeMillis()+".txt");
        try(FileWriter out=new FileWriter(f)){
          out.write("StrataForge Android crash\nUTC: "+now()+"\nDevice: "+Build.MANUFACTURER+" "+Build.MODEL+"\nAndroid: "+Build.VERSION.RELEASE+"\n");
          out.write(strip(trace));
        }
      }catch(Exception ignored){}
    }
  }
}
