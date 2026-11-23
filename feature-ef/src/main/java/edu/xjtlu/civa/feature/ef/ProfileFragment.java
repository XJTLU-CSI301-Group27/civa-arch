package edu.xjtlu.civa.feature.ef;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.google.android.material.button.MaterialButton;
import edu.xjtlu.civa.core.data.ApiCallback;
import edu.xjtlu.civa.core.ui.ApiError;
import edu.xjtlu.civa.core.ui.Route;

/** "我的": account, current household entry point and sign-out. */
public final class ProfileFragment extends Fragment {
    public ProfileFragment() { super(R.layout.ef_fragment_profile); }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);
        ProfileHost host = (ProfileHost) requireActivity();
        TextView account = view.findViewById(R.id.ef_profile_account);
        TextView household = view.findViewById(R.id.ef_profile_household);
        TextView note = view.findViewById(R.id.ef_profile_note);
        MaterialButton settings = view.findViewById(R.id.ef_profile_household_action);
        MaterialButton logout = view.findViewById(R.id.ef_profile_logout);

        String userId = host.sessions().currentUserId();
        String householdId = host.sessions().currentHouseholdId();
        account.setText(userId == null || userId.isEmpty()
                ? getString(R.string.ef_profile_signed_out) : shortId(userId));
        boolean joined = householdId != null && !householdId.isEmpty();
        household.setText(joined ? shortId(householdId) : getString(R.string.ef_profile_no_household));
        note.setText(joined ? R.string.ef_profile_household_note : R.string.ef_profile_household_missing);
        settings.setText(joined ? R.string.ef_profile_household_settings : R.string.ef_profile_household_join);
        settings.setOnClickListener(ignored -> host.navigate(Route.HOUSEHOLD, null));
        logout.setOnClickListener(ignored -> host.authRepository().logout(new ApiCallback<Void>() {
            @Override public void onSuccess(@Nullable Void value) {
                host.navigate(Route.LOGIN, null);
            }

            @Override public void onError(ApiError error) {
                host.navigate(Route.LOGIN, null);
            }
        }));
    }

    private static String shortId(String id) {
        return id.length() <= 8 ? id : id.substring(0, 8);
    }
}
