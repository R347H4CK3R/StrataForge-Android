package dev.strataforge.android;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ConfigurationInfo;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.LinkedHashSet;
import java.util.Set;

public class MainActivity extends Activity {
    private static final int PICK_FILE = 3107;
    private LinearLayout content;
    private TextView status;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        render();
    }

    private TextView text(String value, int size) {
        TextView t=new TextView(this);
        t.setText(value);
        t.setTextColor(Color.WHITE);
        t.setTextSize(size);
        t.setPadding(14,12,14,12);
        return t;
    }

    private Button button(String title, View.OnClickListener action) {
        Button b=new Button(this);
        b.setText(title);
        b.setAllCaps(false);
        b.setOnClickListener(action);
        return b;
    }

    private void render() {
        ScrollView scroll=new ScrollView(this);
        content=new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(18,18,18,18);
        content.setBackgroundColor(Color.rgb(13,18,27));
        scroll.addView(content);
        setContentView(scroll);
        content.addView(text("STRATAFORGE",28));
        content.addView(text("ANDROID • EXPERIMENTAL",15));
        content.addView(text("Game runtime: NOT INSTALLED",19));
        content.addView(text("This build inventories game files and checks device support. It cannot execute Windows games or iOS applications.",15));
        content.addView(button("Import game file", v->chooseFile()));
        content.addView(button("Refresh diagnostics", v->render()));
        content.addView(text("DEVICE DIAGNOSTICS",21));
        content.addView(text(diagnostics(),15));
        content.addView(text("IMPORTED FILES",21));
        SharedPreferences pref=getSharedPreferences("strataforge_files",0);
        Set<String> entries=pref.getStringSet("files",new LinkedHashSet<>());
        if(entries.isEmpty())content.addView(text("No files imported.",14));
        for(String value:entries) {
            String[] bits=value.split("\\|",2);
            LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.VERTICAL);
            row.addView(text(bits.length>1?bits[0]:value,16));
            String entry=value;
            row.addView(button("Remove from library",v->{
                Set<String> e=new LinkedHashSet<>(getSharedPreferences("strataforge_files",0).getStringSet("files",new LinkedHashSet<>()));
                e.remove(entry);getSharedPreferences("strataforge_files",0).edit().putStringSet("files",e).apply();render();
            }));
            content.addView(row);
        }
        status=text("Ready for runtime development and device testing.",13);
        content.addView(status);
    }

    private String diagnostics() {
        StringBuilder out=new StringBuilder();
        out.append("Manufacturer: ").append(Build.MANUFACTURER).append("\n");
        out.append("Model: ").append(Build.MODEL).append("\n");
        out.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        out.append("ABI: ").append(String.join(", ",Build.SUPPORTED_ABIS)).append("\n");
        ActivityManager am=(ActivityManager)getSystemService(ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mem=new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mem);
        out.append("Available RAM: ").append(mem.availMem/1048576).append(" MiB\n");
        out.append("Total RAM: ").append(mem.totalMem/1048576).append(" MiB\n");
        ConfigurationInfo info=am.getDeviceConfigurationInfo();
        out.append("OpenGL ES: ").append(info.getGlEsVersion()).append("\n");
        out.append("Windows execution runtime: unavailable\n");
        out.append("JIT/native translator: not bundled\n");
        out.append("Game graphics backend: not integrated");
        return out.toString();
    }

    private void chooseFile() {
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i,PICK_FILE);
    }

    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request!=PICK_FILE || result!=RESULT_OK || data==null || data.getData()==null)return;
        Uri uri=data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);
            String title=uri.getLastPathSegment();
            try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)) {
                if(c!=null && c.moveToFirst())title=c.getString(0);
            }
            if(title==null)title="Unnamed file";
            Set<String> entries=new LinkedHashSet<>(getSharedPreferences("strataforge_files",0).getStringSet("files",new LinkedHashSet<>()));
            entries.add(title+"|"+uri.toString());
            getSharedPreferences("strataforge_files",0).edit().putStringSet("files",entries).apply();
            render();
        } catch(Exception ex) {
            status.setText("Import failed: "+ex.getClass().getSimpleName()+": "+ex.getMessage());
        }
    }
}
