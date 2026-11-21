package edu.xjtlu.civa.feature.bd;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/** Copies only a user-selected photo into private storage; no broad photo-library permission. */
final class BdPhotoFiles {
    private BdPhotoFiles() { }

    static String importPhoto(Context context, Uri uri, String scope) throws IOException {
        File temporary = File.createTempFile("dish-import-", ".image", context.getCacheDir());
        Bitmap decoded = null;
        try {
            try (InputStream input = context.getContentResolver().openInputStream(uri);
                 FileOutputStream output = new FileOutputStream(temporary)) {
                if (input == null) throw new IOException("Cannot open selected image");
                byte[] buffer = new byte[8192];
                int count, total = 0;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > 20 * 1024 * 1024) throw new IOException("Photo exceeds 20 MB");
                    output.write(buffer, 0, count);
                }
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(temporary.getPath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || (long) bounds.outWidth * bounds.outHeight > 100_000_000L) throw new IOException("Unsupported photo");
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 1600) options.inSampleSize *= 2;
            decoded = BitmapFactory.decodeFile(temporary.getPath(), options);
            if (decoded == null) throw new IOException("Cannot decode selected image");
            ExifInterface exif = new ExifInterface(temporary.getPath());
            int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            Matrix matrix = new Matrix();
            switch (orientation) {
                case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: matrix.setScale(-1, 1); break;
                case ExifInterface.ORIENTATION_ROTATE_180: matrix.setRotate(180); break;
                case ExifInterface.ORIENTATION_FLIP_VERTICAL: matrix.setScale(1, -1); break;
                case ExifInterface.ORIENTATION_TRANSPOSE: matrix.setRotate(90); matrix.postScale(-1, 1); break;
                case ExifInterface.ORIENTATION_ROTATE_90: matrix.setRotate(90); break;
                case ExifInterface.ORIENTATION_TRANSVERSE: matrix.setRotate(270); matrix.postScale(-1, 1); break;
                case ExifInterface.ORIENTATION_ROTATE_270: matrix.setRotate(270); break;
                default: break;
            }
            if (!matrix.isIdentity()) {
                Bitmap upright = Bitmap.createBitmap(decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), matrix, true);
                if (upright != decoded) { decoded.recycle(); decoded = upright; }
            }
            File directory = new File(context.getFilesDir(), "home-dish-photos/" + scope);
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create photo storage");
            File destination = new File(directory, UUID.randomUUID() + ".jpg");
            try (FileOutputStream output = new FileOutputStream(destination)) {
                if (!decoded.compress(Bitmap.CompressFormat.JPEG, 88, output)) throw new IOException("Cannot save selected image");
                output.getFD().sync();
            }
            return "local:home-dish-photos/" + scope + "/" + destination.getName();
        } finally {
            if (decoded != null) decoded.recycle();
            if (!temporary.delete() && temporary.exists()) temporary.deleteOnExit();
        }
    }

    static InputStream open(Context context, String reference) throws IOException {
        if (reference.startsWith("asset:recipes/images/") && !reference.contains("..")) return context.getAssets().open(reference.substring(6));
        if (!reference.startsWith("local:home-dish-photos/")) throw new IOException("Unknown photo reference");
        File file = new File(context.getFilesDir(), reference.substring(6));
        String root = new File(context.getFilesDir(), "home-dish-photos").getCanonicalPath() + File.separator;
        if (!file.getCanonicalPath().startsWith(root)) throw new IOException("Photo outside private storage");
        return new FileInputStream(file);
    }

    static void discard(Context context, String reference) {
        if (reference == null || !reference.startsWith("local:home-dish-photos/")) return;
        try {
            File file = new File(context.getFilesDir(), reference.substring(6));
            String root = new File(context.getFilesDir(), "home-dish-photos").getCanonicalPath() + File.separator;
            if (file.getCanonicalPath().startsWith(root) && file.exists() && !file.delete()) file.deleteOnExit();
        } catch (IOException ignored) { /* A later app-data cleanup can remove an unreferenced draft image. */ }
    }
}
