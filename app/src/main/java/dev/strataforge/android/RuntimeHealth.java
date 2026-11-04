package dev.strataforge.android;

import android.content.Context;
import android.os.StatFs;
import android.os.SystemClock;
import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.Security;
import java.security.Provider;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/** Preflight and progress checks designed to flag known failures before a Steam download crashes. */
final class RuntimeHealth {
  private static final long MIN_FREE=512L*1024*1024;
  private static final long WARN_STALLED_MS=180000L;
  private static final AtomicBoolean CHECK_RUNNING=new AtomicBoolean(false);
  private static volatile long lastProgress=0;
  private static volatile boolean downloading=false;
  private static volatile int gameId=0;
  private static volatile boolean stallReported=false;
  private RuntimeHealth(){}

  static class Result {
    final boolean ok;
    final String detail;
    Result(boolean ok,String detail){this.ok=ok;this.detail=detail;}
  }
  static Result preflight(Context context,File folder){
    try{
      if(folder==null)throw new IllegalStateException("Game storage unavailable");
      if(!folder.isDirectory()&&!folder.mkdirs())throw new IllegalStateException("Cannot create game directory");
      long available=new StatFs(folder.getAbsolutePath()).getAvailableBytes();
      if(available<MIN_FREE)throw new IllegalStateException("Low storage: "+(available/1048576)+" MiB free; reserve at least 512 MiB");
      Provider provider=Security.getProvider("BC");
      if(!(provider instanceof BouncyCastleProvider)){
        Diagnostics.log("health-fallback","Attempting to restore full BouncyCastle provider");
        if(provider!=null)Security.removeProvider("BC");
        Security.addProvider(new BouncyCastleProvider());
        provider=Security.getProvider("BC");
        if(!(provider instanceof BouncyCastleProvider))
          throw new IllegalStateException("Full BouncyCastle provider registration failed");
      }
      byte[] expected=hex("a9993e364706816aba3e25717850c26c9cd0d89d");
      if(!Arrays.equals(expected,MessageDigest.getInstance("SHA-1","BC").digest("abc".getBytes("UTF-8"))))
        throw new IllegalStateException("Steam SHA-1 verification failed");
      byte[] original="StrataForge Zstd test".getBytes("UTF-8");
      byte[] packed=com.github.luben.zstd.Zstd.compress(original);
      if(!Arrays.equals(original,com.github.luben.zstd.Zstd.decompress(packed,original.length)))
        throw new IllegalStateException("Zstd JNI round-trip failed");
      // Fail safely before Steam reaches any XZ/LZMA encoded depot chunk.
      Class.forName("org.tukaani.xz.LZMAInputStream");
      Diagnostics.log("health","XZ/LZMA decoder found");
      Diagnostics.log("health","Download prerequisites passed; free MiB="+available/1048576);
      return new Result(true,"All download prerequisites passed");
    }catch(Throwable problem){
      Diagnostics.error("health-preflight",new IllegalStateException("Download blocked before failure: "+problem.getMessage()));
      return new Result(false,"Download blocked: "+problem.getClass().getSimpleName()+": "+problem.getMessage());
    }
  }
  static boolean recoverable(Throwable failure){
    Throwable cause=failure;
    int depth=0;
    while(cause!=null&&depth++<8){
      if(cause instanceof java.net.SocketTimeoutException ||
         cause instanceof java.net.ConnectException ||
         cause instanceof java.net.UnknownHostException ||
         cause instanceof java.io.InterruptedIOException)
         return true;
      if(cause instanceof LinkageError ||
         cause instanceof java.security.GeneralSecurityException ||
         cause instanceof SecurityException)
         return false;
      cause=cause.getCause();
    }
    return false;
  }
  static Result retryCheck(Context context,File folder){
    try{
      return preflight(context,folder);
    }catch(Exception ex){return new Result(false,"Retry preparation failed: "+ex.getMessage());}
  }
  static void startDownload(int appId){
    gameId=appId;downloading=true;stallReported=false;lastProgress=SystemClock.elapsedRealtime();
    Diagnostics.log("health","Download "+appId+" monitoring started");
  }
  static void progress(){
    lastProgress=SystemClock.elapsedRealtime();stallReported=false;
  }
  static void finishDownload(){downloading=false;gameId=0;stallReported=false;}
  static void check(Context c){
    if(!CHECK_RUNNING.compareAndSet(false,true))return;
    try{
      if(downloading){
        long age=SystemClock.elapsedRealtime()-lastProgress;
        if(age>WARN_STALLED_MS&&!stallReported){
          stallReported=true;
          Diagnostics.log("health","Download "+gameId+" has made no reported progress for "+age/1000+" seconds; monitor network/storage");
        }
        File root=c.getExternalFilesDir(null);
        if(root!=null){
          long free=new StatFs(root.getAbsolutePath()).getAvailableBytes();
          if(free<MIN_FREE&&!stallReported){
            stallReported=true;
            Diagnostics.error("health-storage",new IllegalStateException("Game "+gameId+" low available space: "+free/1048576+" MiB"));
          }
        }
      }
    }catch(Exception e){Diagnostics.error("health-watchdog",e);}
    finally{CHECK_RUNNING.set(false);}
  }
  private static byte[] hex(String s){
    byte[] result=new byte[s.length()/2];
    for(int i=0;i<result.length;i++)result[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16);
    return result;
  }
}
