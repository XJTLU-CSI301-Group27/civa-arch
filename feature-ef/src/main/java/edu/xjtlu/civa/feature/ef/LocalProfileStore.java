package edu.xjtlu.civa.feature.ef;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.json.JSONObject;

/** Display-only local profile, scoped by authenticated account, never a login-name mutation. */
final class LocalProfileStore {
    private final Context context;
    private final File directory;
    private final String scope;
    LocalProfileStore(Context context,String userId){
        this.context=context.getApplicationContext();
        scope=ProfileIdentity.scope(userId);
        directory=new File(context.getFilesDir(),"profiles/"+scope);
    }
    String name(){return read().optString("name","");}
    File avatar(){String file=read().optString("avatar","");return ProfileIdentity.avatarFile(file)?new File(directory,file):null;}
    private JSONObject read(){
        try{byte[] bytes=new AtomicFile(new File(directory,"profile.json")).readFully();JSONObject json=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
            return json.optInt("version")==1&&scope.equals(json.optString("scope"))?json:new JSONObject();}
        catch(Exception ignored){return new JSONObject();}
    }
    File importPhoto(Uri uri)throws Exception{
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
        try(InputStream input=context.getContentResolver().openInputStream(uri)){BitmapFactory.decodeStream(input,null,bounds);}
        if(bounds.outWidth<=0||bounds.outHeight<=0||bounds.outWidth>24000||bounds.outHeight>24000)throw new IllegalArgumentException("Invalid image");
        BitmapFactory.Options options=new BitmapFactory.Options(); options.inSampleSize=1;
        while(Math.max(bounds.outWidth,bounds.outHeight)/options.inSampleSize>640)options.inSampleSize*=2;
        Bitmap bitmap;
        try(InputStream input=context.getContentResolver().openInputStream(uri)){bitmap=BitmapFactory.decodeStream(input,null,options);}
        if(bitmap==null)throw new IllegalArgumentException("Invalid image");
        try(InputStream input=context.getContentResolver().openInputStream(uri)){
            int orientation=new android.media.ExifInterface(input).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,1);
            android.graphics.Matrix matrix=new android.graphics.Matrix();
            switch(orientation){case 2:matrix.setScale(-1,1);break;case 3:matrix.setRotate(180);break;case 4:matrix.setScale(1,-1);break;
                case 5:matrix.setRotate(90);matrix.postScale(-1,1);break;case 6:matrix.setRotate(90);break;case 7:matrix.setRotate(-90);matrix.postScale(-1,1);break;case 8:matrix.setRotate(-90);break;default:break;}
            if(!matrix.isIdentity()){Bitmap corrected=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),matrix,true);if(corrected!=bitmap){bitmap.recycle();bitmap=corrected;}}
        }catch(java.io.IOException unsupportedMetadata){ /* PNG and other images can have no EXIF. */ }
        File pending=new File(context.getCacheDir(),"ef-avatar-"+scope+"-"+UUID.randomUUID()+".jpg");
        try(FileOutputStream out=new FileOutputStream(pending)){if(!bitmap.compress(Bitmap.CompressFormat.JPEG,90,out))throw new IllegalStateException("Image compression failed");}
        finally{bitmap.recycle();}
        return pending;
    }
    boolean save(String name,File pending){
        String trimmed;try{trimmed=ProfileIdentity.normalizedName(name);}catch(IllegalArgumentException invalid){return false;}
        AtomicFile metadata=new AtomicFile(new File(directory,"profile.json")); FileOutputStream output=null;File photo=null;
        try{
            if(!directory.exists()&&!directory.mkdirs())return false;
            JSONObject previous=read();String reference=previous.optString("avatar","");
            if(pending!=null){
                String prefix="ef-avatar-"+scope+"-";
                if(!pending.getCanonicalFile().getParentFile().equals(context.getCacheDir().getCanonicalFile())||!pending.getName().startsWith(prefix))return false;
                photo=new File(directory,"avatar-"+UUID.randomUUID()+".jpg");
                try(InputStream in=new FileInputStream(pending);FileOutputStream out=new FileOutputStream(photo)){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);out.getFD().sync();}
                reference=photo.getName();
            }
            JSONObject record=new JSONObject().put("version",1).put("scope",scope).put("name",trimmed).put("avatar",reference);
            output=metadata.startWrite();output.write(record.toString().getBytes(StandardCharsets.UTF_8));metadata.finishWrite(output);output=null;
            return name().equals(trimmed);
        }catch(Exception error){if(output!=null)metadata.failWrite(output);if(photo!=null)photo.delete();return false;}
    }
}
