package edu.xjtlu.civa.feature.ef;

import android.app.Dialog;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.fragment.app.DialogFragment;
import com.google.android.material.button.MaterialButton;
import edu.xjtlu.civa.api.model.UpdateProfileRequest;
import edu.xjtlu.civa.api.model.User;
import edu.xjtlu.civa.core.data.ApiCallback;
import edu.xjtlu.civa.core.ui.ApiError;
import edu.xjtlu.civa.core.ui.PressBounce;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Framework-recreated editor; photo picking follows the existing OpenDocument pattern. */
public final class ProfileEditDialog extends DialogFragment {
    private LocalProfileStore store;
    private String owner;
    private EditText name;
    private ImageView avatar;
    private TextView error;
    private MaterialButton save;
    private File pending;
    private boolean importing;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ExecutorService worker;
    private final ActivityResultLauncher<String[]> picker = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
        if(uri==null||store==null||worker==null||worker.isShutdown())return;
        LocalProfileStore target=store; importing=true;save.setEnabled(false);error.setText(R.string.ef_photo_loading);
        worker.execute(()->{
            File photo=null;try{photo=target.importPhoto(uri);}catch(Exception ignored){ }
            File imported=photo;
            main.post(()->{
                if(!isAdded()||getDialog()==null||avatar==null){if(imported!=null)imported.delete();return;}
                importing=false;save.setEnabled(true);
                if(imported==null){error.setText(R.string.ef_photo_error);return;}
                pending=imported;error.setText("");paintAvatar(avatar,pending);
            });
        });
    });
    static ProfileEditDialog create(String userId,String displayed){
        ProfileEditDialog dialog=new ProfileEditDialog();Bundle args=new Bundle();args.putString("owner",userId);args.putString("name",displayed);dialog.setArguments(args);return dialog;
    }
    @NonNull @Override public Dialog onCreateDialog(@Nullable Bundle state){
        owner=requireArguments().getString("owner");store=new LocalProfileStore(requireContext(),owner);worker=Executors.newSingleThreadExecutor();
        LinearLayout body=new LinearLayout(requireContext());body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(22),dp(14),dp(22),dp(24));
        GradientDrawable surface=new GradientDrawable();surface.setColor(color(edu.xjtlu.civa.core.ui.R.color.civa_surface));surface.setCornerRadius(dp(28));surface.setStroke(dp(1),color(edu.xjtlu.civa.core.ui.R.color.civa_outline));body.setBackground(surface);
        FrameLayout header=new FrameLayout(requireContext());body.addView(header,new LinearLayout.LayoutParams(-1,dp(52)));
        TextView title=new TextView(requireContext());title.setText(R.string.ef_edit_profile);title.setTextSize(22);title.setTypeface(ResourcesCompat.getFont(requireContext(),edu.xjtlu.civa.core.ui.R.font.civa_kuaile));title.setTextColor(color(edu.xjtlu.civa.core.ui.R.color.civa_text));title.setGravity(Gravity.CENTER);
        header.addView(title,new FrameLayout.LayoutParams(-1,-1));
        MaterialButton close=new MaterialButton(requireContext(),null,com.google.android.material.R.attr.borderlessButtonStyle);close.setId(R.id.ef_profile_edit_close);close.setText(R.string.ef_close_symbol);close.setTextSize(20);close.setMinWidth(0);close.setMinimumWidth(0);close.setPadding(0,0,0,0);close.setContentDescription(getString(R.string.ef_close));
        header.addView(close,new FrameLayout.LayoutParams(dp(40),dp(48),Gravity.START|Gravity.CENTER_VERTICAL));close.setOnClickListener(v->dismiss());
        avatar=new ImageView(requireContext());avatar.setId(R.id.ef_profile_edit_avatar);avatar.setContentDescription(getString(R.string.ef_change_avatar));avatar.setClickable(true);avatar.setFocusable(true);
        LinearLayout.LayoutParams picture=new LinearLayout.LayoutParams(dp(112),dp(112));picture.gravity=Gravity.CENTER_HORIZONTAL;picture.topMargin=dp(16);picture.bottomMargin=dp(8);body.addView(avatar,picture);
        if(state!=null){String path=state.getString("photo");if(path!=null&&new File(path).getParentFile().equals(requireContext().getCacheDir()))pending=new File(path);}
        paintAvatar(avatar,pending==null?store.avatar():pending);PressBounce.bind(avatar);avatar.setOnClickListener(v->picker.launch(new String[]{"image/*"}));
        TextView change=new TextView(requireContext());change.setText(R.string.ef_change_avatar);change.setGravity(Gravity.CENTER);change.setTextColor(color(edu.xjtlu.civa.core.ui.R.color.civa_muted));body.addView(change);
        TextView label=new TextView(requireContext());label.setText(R.string.ef_username);label.setTextSize(16);label.setTextColor(color(edu.xjtlu.civa.core.ui.R.color.civa_text));LinearLayout.LayoutParams labelParams=new LinearLayout.LayoutParams(-1,-2);labelParams.topMargin=dp(24);body.addView(label,labelParams);
        name=new EditText(requireContext());name.setId(R.id.ef_profile_edit_name);
        name.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PERSON_NAME | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        name.setSingleLine(true);name.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);name.setSelectAllOnFocus(true);
        name.setOnEditorActionListener((v,action,event)->{if(action==android.view.inputmethod.EditorInfo.IME_ACTION_DONE){((android.view.inputmethod.InputMethodManager)requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(name.getWindowToken(),0);return true;}return false;});
        name.setTextSize(18);name.setTextColor(color(edu.xjtlu.civa.core.ui.R.color.civa_text));name.setPadding(dp(14),dp(12),dp(14),dp(12));name.setTypeface(ResourcesCompat.getFont(requireContext(),edu.xjtlu.civa.core.ui.R.font.civa_kuaile));
        GradientDrawable input=new GradientDrawable();input.setColor(color(edu.xjtlu.civa.core.ui.R.color.civa_background));input.setCornerRadius(dp(18));name.setBackground(input);name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(60)});
        name.setText(state==null?requireArguments().getString("name",""):state.getString("name",""));body.addView(name,new LinearLayout.LayoutParams(-1,dp(56)));
        error=new TextView(requireContext());error.setTextColor(color(edu.xjtlu.civa.core.ui.R.color.civa_primary_dark));error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);body.addView(error,new LinearLayout.LayoutParams(-1,dp(34)));
        save=new MaterialButton(requireContext());save.setId(R.id.ef_profile_edit_save);save.setText(R.string.ef_profile_save);save.setTextSize(18);save.setTypeface(ResourcesCompat.getFont(requireContext(),edu.xjtlu.civa.core.ui.R.font.civa_kuaile));save.setCornerRadius(dp(27));body.addView(save,new LinearLayout.LayoutParams(-1,dp(56)));
        save.setOnClickListener(v->{
            if(importing||!owner.equals(((ProfileHost)requireActivity()).sessions().currentUserId()))return;
            ProfileHost host=(ProfileHost)requireActivity();
            String entered=name.getText().toString().trim();
            if(entered.isEmpty()){error.setText(R.string.ef_profile_save_error);return;}
            save.setEnabled(false);error.setText("");
            host.calls().enqueue(host.api().profile().updateProfile(new UpdateProfileRequest().displayName(entered)),
                    new ApiCallback<User>(){
                        @Override public void onSuccess(@Nullable User value){
                            if(!isAdded())return;
                            if(!store.save(entered,pending)){error.setText(R.string.ef_profile_save_error);save.setEnabled(true);return;}
                            getParentFragmentManager().setFragmentResult("ef_profile_changed",new Bundle());dismiss();
                        }
                        @Override public void onError(ApiError apiError){
                            if(!isAdded())return;
                            save.setEnabled(true);error.setText(R.string.ef_profile_save_error);
                        }
                    });
        });
        ScrollView scroll=new ScrollView(requireContext());scroll.setFillViewport(false);scroll.addView(body);Dialog dialog=new AlertDialog.Builder(requireContext()).setView(scroll).create();return dialog;
    }
    @Override public void onStart(){super.onStart();if(getDialog()!=null&&getDialog().getWindow()!=null){getDialog().getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));getDialog().getWindow().setLayout(Math.min(dp(384),getResources().getDisplayMetrics().widthPixels-dp(32)),ViewGroup.LayoutParams.WRAP_CONTENT);getDialog().getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);PressBounce.bindButtons(getDialog().getWindow().getDecorView());}}
    @Override public void onSaveInstanceState(@NonNull Bundle state){super.onSaveInstanceState(state);if(name!=null)state.putString("name",name.getText().toString());if(pending!=null)state.putString("photo",pending.getAbsolutePath());}
    @Override public void onDestroyView(){avatar=null;name=null;save=null;error=null;if(worker!=null){worker.shutdownNow();worker=null;}super.onDestroyView();}
    static void paintAvatar(ImageView view,File file){
        GradientDrawable oval=new GradientDrawable();oval.setShape(GradientDrawable.OVAL);oval.setColor(ContextCompat.getColor(view.getContext(),edu.xjtlu.civa.core.ui.R.color.civa_background));oval.setStroke(2,ContextCompat.getColor(view.getContext(),edu.xjtlu.civa.core.ui.R.color.civa_outline));view.setBackground(oval);view.setClipToOutline(true);view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if(file!=null&&file.isFile()){view.setPadding(0,0,0,0);view.setImageBitmap(BitmapFactory.decodeFile(file.getAbsolutePath()));}
        else{view.setScaleType(ImageView.ScaleType.FIT_CENTER);int padding=Math.round(6*view.getResources().getDisplayMetrics().density);view.setPadding(padding,padding,padding,padding);view.setImageResource(edu.xjtlu.civa.core.ui.R.drawable.civa_cat_egg);}
    }
    private int color(int id){return ContextCompat.getColor(requireContext(),id);}
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
}
