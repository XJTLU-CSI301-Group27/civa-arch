package edu.xjtlu.civa.feature.ef;

import android.app.Dialog;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import com.google.android.material.button.MaterialButton;
import edu.xjtlu.civa.api.model.User;
import edu.xjtlu.civa.core.data.ApiCallback;
import edu.xjtlu.civa.core.ui.ApiError;
import edu.xjtlu.civa.core.ui.PressBounce;
import edu.xjtlu.civa.core.ui.Route;

/** Personal display profile. Credentials and household operations keep their existing contracts. */
public final class ProfileFragment extends Fragment {
    private LocalProfileStore profile;
    private String registeredName="";
    public ProfileFragment(){super(R.layout.ef_fragment_profile);}
    @Override public void onViewCreated(@NonNull View view,@Nullable Bundle state){
        super.onViewCreated(view,state);ProfileHost host=(ProfileHost)requireActivity();String userId=host.sessions().currentUserId();
        if(userId==null){host.navigate(Route.LOGIN,null);return;}
        profile=new LocalProfileStore(requireContext(),userId);
        TextView id=view.findViewById(R.id.ef_profile_id);id.setText(getString(R.string.ef_profile_id,userId));
        TextView household=view.findViewById(R.id.ef_profile_household);TextView note=view.findViewById(R.id.ef_profile_note);
        paintHousehold(view,host);
        view.findViewById(R.id.ef_profile_household_action).setOnClickListener(v->host.navigate(Route.HOUSEHOLD,null));
        view.findViewById(R.id.ef_profile_language).setOnClickListener(v->{if(getChildFragmentManager().findFragmentByTag("language")==null)new LanguageDialog().show(getChildFragmentManager(),"language");});
        view.findViewById(R.id.ef_profile_edit).setOnClickListener(v->{if(getChildFragmentManager().findFragmentByTag("edit-profile")==null)ProfileEditDialog.create(userId,shownName()).show(getChildFragmentManager(),"edit-profile");});
        MaterialButton logout=view.findViewById(R.id.ef_profile_logout);logout.setOnClickListener(v->{logout.setEnabled(false);host.authRepository().logout(new ApiCallback<Void>(){
            @Override public void onSuccess(@Nullable Void value){host.navigate(Route.LOGIN,null);}
            @Override public void onError(ApiError error){host.navigate(Route.LOGIN,null);}
        });});
        getChildFragmentManager().setFragmentResultListener("ef_profile_changed",getViewLifecycleOwner(),(key,result)->paint(view));paint(view);
        host.authRepository().refreshMe(new ApiCallback<User>(){
            @Override public void onSuccess(@Nullable User user){
                if(getView()!=view||user==null||!userId.equals(host.sessions().currentUserId()))return;
                paintHousehold(view,host);
                registeredName=user.getDisplayName()==null||user.getDisplayName().isBlank()?user.getUsername():user.getDisplayName();
                if(profile.name().isBlank())profile.save(registeredName,null);paint(view);
            }
            @Override public void onError(ApiError error){ }
        });
    }
    private void paintHousehold(View view, ProfileHost host) {
        String householdId=host.sessions().currentHouseholdId(); boolean joined=householdId!=null&&!householdId.isEmpty();
        ((TextView)view.findViewById(R.id.ef_profile_household)).setText(joined?getString(R.string.ef_profile_id,householdId):getString(R.string.ef_profile_no_household));
        view.findViewById(R.id.ef_profile_note).setVisibility(joined?View.VISIBLE:View.GONE);
        TextView familyName=view.findViewById(R.id.ef_profile_household_name);familyName.setVisibility(joined?View.VISIBLE:View.GONE);
        if(joined) {
            familyName.setText(R.string.ef_household_name_loading);
            ((HouseholdHost)host).calls().enqueue(((HouseholdHost)host).api().household().getHousehold(java.util.UUID.fromString(householdId)),new ApiCallback<edu.xjtlu.civa.api.model.Household>() {
                @Override public void onSuccess(edu.xjtlu.civa.api.model.Household family){
                    if(getView()==view&&householdId.equals(host.sessions().currentHouseholdId())&&family!=null)familyName.setText(family.getName());
                }
                @Override public void onError(ApiError error){if(getView()==view&&householdId.equals(host.sessions().currentHouseholdId()))familyName.setText(R.string.ef_household_name_unavailable);}
            });
        }
        ((MaterialButton)view.findViewById(R.id.ef_profile_household_action)).setText(joined?R.string.ef_profile_household_settings:R.string.ef_profile_household_join);
    }
    private String shownName(){String local=profile.name();return !local.isBlank()?local:registeredName.isBlank()?getString(R.string.ef_profile_default_name):registeredName;}
    private void paint(View view){if(profile==null)return;((TextView)view.findViewById(R.id.ef_profile_account)).setText(shownName());ProfileEditDialog.paintAvatar((ImageView)view.findViewById(R.id.ef_profile_avatar),profile.avatar());}
    @Override public void onDestroyView(){profile=null;super.onDestroyView();}

    public static final class LanguageDialog extends DialogFragment {
        @NonNull @Override public Dialog onCreateDialog(@Nullable Bundle state){
            LinearLayout body=new LinearLayout(requireContext());body.setOrientation(LinearLayout.VERTICAL);int pad=dp(22);body.setPadding(pad,dp(10),pad,pad);
            MaterialButton close=new MaterialButton(requireContext(),null,com.google.android.material.R.attr.borderlessButtonStyle);close.setId(R.id.ef_language_close);close.setText(R.string.ef_close_symbol);close.setTextSize(20);close.setContentDescription(getString(R.string.ef_close));android.widget.FrameLayout header=new android.widget.FrameLayout(requireContext());body.addView(header,new LinearLayout.LayoutParams(-1,dp(52)));
            TextView title=new TextView(requireContext());title.setId(R.id.ef_language_title);title.setText(R.string.ef_language_title);title.setTextSize(22);title.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(),edu.xjtlu.civa.core.ui.R.color.civa_text));title.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(requireContext(),edu.xjtlu.civa.core.ui.R.font.civa_kuaile));title.setGravity(Gravity.CENTER);header.addView(title,new android.widget.FrameLayout.LayoutParams(-1,-1));
            header.addView(close,new android.widget.FrameLayout.LayoutParams(dp(44),dp(48),Gravity.START|Gravity.CENTER_VERTICAL));close.setOnClickListener(v->dismiss());
            for(int i=0;i<2;i++){MaterialButton button=new MaterialButton(requireContext());button.setId(i==0?R.id.ef_language_zh:R.id.ef_language_en);button.setText(i==0?R.string.ef_language_chinese:R.string.ef_language_english);button.setTextSize(22);button.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(requireContext(),edu.xjtlu.civa.core.ui.R.font.civa_kuaile));button.setCornerRadius(dp(25));LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,dp(60));bp.topMargin=dp(12);body.addView(button,bp);button.setOnClickListener(v->{ });}
            android.graphics.drawable.GradientDrawable surface=new android.graphics.drawable.GradientDrawable();surface.setColor(androidx.core.content.ContextCompat.getColor(requireContext(),edu.xjtlu.civa.core.ui.R.color.civa_surface));surface.setCornerRadius(dp(28));body.setBackground(surface);
            return new AlertDialog.Builder(requireContext()).setView(body).create();
        }
        @Override public void onStart(){super.onStart();if(getDialog()!=null&&getDialog().getWindow()!=null){getDialog().getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));getDialog().getWindow().setLayout(Math.min(dp(340),getResources().getDisplayMetrics().widthPixels-dp(40)),ViewGroup.LayoutParams.WRAP_CONTENT);PressBounce.bindButtons(getDialog().getWindow().getDecorView());}}
        private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    }
}
