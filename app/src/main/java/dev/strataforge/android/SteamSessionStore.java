package dev.strataforge.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Stores Steam login refresh token encrypted by the Android Keystore. */
final class SteamSessionStore {
  private static final String ALIAS="strataforge.steam.refresh.v1";
  private static final String PREF="steam_session_v1";
  private SteamSessionStore(){}
  private static SecretKey key() throws Exception {
    KeyStore store=KeyStore.getInstance("AndroidKeyStore");
    store.load(null);
    KeyStore.Entry entry=store.getEntry(ALIAS,null);
    if(entry instanceof KeyStore.SecretKeyEntry)return ((KeyStore.SecretKeyEntry)entry).getSecretKey();
    KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
    generator.init(new KeyGenParameterSpec.Builder(ALIAS,
        KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256).build());
    return generator.generateKey();
  }
  static void save(Context c,String account,String refreshToken)throws Exception{
    if(account==null||account.isEmpty()||refreshToken==null||refreshToken.isEmpty())
      throw new IllegalArgumentException("Missing Steam session");
    Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE,key());
    byte[] iv=cipher.getIV();
    byte[] data=cipher.doFinal(refreshToken.getBytes(StandardCharsets.UTF_8));
    SharedPreferences prefs=c.getSharedPreferences(PREF,Context.MODE_PRIVATE);
    if(!prefs.edit().putString("account",account)
      .putString("iv",Base64.encodeToString(iv,Base64.NO_WRAP))
      .putString("refresh",Base64.encodeToString(data,Base64.NO_WRAP)).commit())
      throw new IllegalStateException("Failed to save Steam login");
  }
  static String[] read(Context c)throws Exception{
    SharedPreferences prefs=c.getSharedPreferences(PREF,Context.MODE_PRIVATE);
    String account=prefs.getString("account",null);
    String encrypted=prefs.getString("refresh",null);
    String iv=prefs.getString("iv",null);
    if(account==null||encrypted==null||iv==null)return null;
    Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(iv,Base64.DEFAULT)));
    return new String[]{account,new String(cipher.doFinal(Base64.decode(encrypted,Base64.DEFAULT)),StandardCharsets.UTF_8)};
  }
  static void clear(Context c){c.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit().clear().commit();}
}
