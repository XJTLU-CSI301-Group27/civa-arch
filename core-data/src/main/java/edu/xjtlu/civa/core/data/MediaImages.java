package edu.xjtlu.civa.core.data;

import android.content.Context;
import android.content.ContentResolver;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.widget.ImageView;
import edu.xjtlu.civa.api.model.Media;
import edu.xjtlu.civa.api.model.MediaScope;
import edu.xjtlu.civa.core.data.ApiCallback;
import edu.xjtlu.civa.core.data.ApiCalls;
import edu.xjtlu.civa.core.data.ApiProvider;
import edu.xjtlu.civa.core.ui.ApiError;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;

/** Small bridge between the system photo picker, /media and recipe image views. */
public final class MediaImages {
    public interface Uploaded { void done(UUID imageId); }

    private MediaImages() { }

    public static void upload(Context context, ApiProvider api, ApiCalls calls, Uri uri, MediaScope scope,
                       UUID householdId, Uploaded callback) {
        try {
            ContentResolver resolver = context.getContentResolver();
            String mime = resolver.getType(uri);
            if (!"image/png".equals(mime)) mime = "image/jpeg";
            byte[] bytes;
            try (InputStream input = resolver.openInputStream(uri)) {
                if (input == null) { callback.done(null); return; }
                java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int read;
                while ((read = input.read(chunk)) != -1) buffer.write(chunk, 0, read);
                bytes = buffer.toByteArray();
            }
            if (bytes.length == 0 || bytes.length > 5 * 1024 * 1024) { callback.done(null); return; }
            RequestBody body = RequestBody.create(bytes, MediaType.parse(mime));
            MultipartBody.Part part = MultipartBody.Part.createFormData("file",
                    "recipe" + ("image/png".equals(mime) ? ".png" : ".jpg"), body);
            calls.enqueue(api.media().uploadMedia(UUID.randomUUID(), part, scope, householdId),
                    new ApiCallback<Media>() {
                        @Override public void onSuccess(Media value) {
                            callback.done(value == null ? null : value.getId());
                        }

                        @Override public void onError(ApiError error) {
                            callback.done(null);
                        }
                    });
        } catch (IOException | RuntimeException invalid) {
            callback.done(null);
        }
    }

    public static void load(ApiProvider api, ApiCalls calls, UUID imageId, ImageView view) {
        if (imageId == null) {
            view.setVisibility(ImageView.GONE);
            return;
        }
        calls.enqueue(api.media().getMediaContent(imageId), new ApiCallback<ResponseBody>() {
            @Override public void onSuccess(ResponseBody body) {
                if (body == null) { view.setVisibility(ImageView.GONE); return; }
                try (ResponseBody closeable = body) {
                    byte[] bytes = closeable.bytes();
                    android.graphics.Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    if (bitmap == null) { view.setVisibility(ImageView.GONE); return; }
                    view.setImageBitmap(bitmap);
                    view.setVisibility(ImageView.VISIBLE);
                } catch (IOException invalid) {
                    view.setVisibility(ImageView.GONE);
                }
            }

            @Override public void onError(ApiError error) {
                view.setVisibility(ImageView.GONE);
            }
        });
    }
}
