package edu.xjtlu.civa.feature.ef;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import edu.xjtlu.civa.feature.ef.databinding.EfFragmentHouseholdBinding;
import java.util.function.Consumer;

public final class HouseholdFragment extends Fragment {
    private HouseholdViewModel model;
    private EfFragmentHouseholdBinding binding;

    public HouseholdFragment() { super(R.layout.ef_fragment_household); }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);
        HouseholdHost host = (HouseholdHost) requireActivity();
        model = new ViewModelProvider(this, new ViewModelProvider.Factory() {
            @NonNull @Override public <T extends ViewModel> T create(@NonNull Class<T> type) {
                if (type != HouseholdViewModel.class) throw new IllegalArgumentException("Unknown ViewModel");
                return type.cast(new HouseholdViewModel(host.api(), host.calls(), host.authRepository(), host.sessions()));
            }
        }).get(HouseholdViewModel.class);
        binding = EfFragmentHouseholdBinding.bind(view);
        ((edu.xjtlu.civa.core.ui.BakeryWelcomeLayout)view.findViewById(R.id.ef_welcome_scene)).setGreeting(getString(R.string.ef_household_greeting));
        binding.efHouseholdName.setText(model.name);
        binding.efHouseholdCode.setText(model.code);
        binding.efHouseholdName.addTextChangedListener(watcher(value -> model.name = value));
        binding.efHouseholdCode.addTextChangedListener(watcher(value -> model.code = value));
        model.recover();
        binding.efSetupControls.setVisibility(model.joined ? View.GONE : View.VISIBLE);
        binding.efJoinedControls.setVisibility(model.joined ? View.VISIBLE : View.GONE);
        binding.efHouseholdDetail.setVisibility(model.joined ? View.GONE : View.VISIBLE);
        if(model.joined) {
            binding.efHouseholdHeading.setText(R.string.ef_edit_household_name);
            binding.efHouseholdContent.setGravity(android.view.Gravity.TOP);
            int top=Math.round(30*getResources().getDisplayMetrics().density);
            binding.efHouseholdContent.setPadding(binding.efHouseholdContent.getPaddingLeft(),top,binding.efHouseholdContent.getPaddingRight(),top);
        }
        binding.efEditHouseholdName.setText(model.editedName);
        binding.efEditHouseholdName.addTextChangedListener(watcher(value -> model.editName(value)));
        model.householdName.observe(getViewLifecycleOwner(), value -> {
            if(!binding.efEditHouseholdName.getText().toString().equals(model.editedName))binding.efEditHouseholdName.setText(model.editedName);
            binding.efSaveHouseholdName.setEnabled(model.canSaveName());
        });
        binding.efSaveHouseholdName.setOnClickListener(ignored -> {dismissKeyboard();model.saveName();});
        model.invitation.observe(getViewLifecycleOwner(), value -> {
            if(value!=null){binding.efInvitationCode.setText(value.getCode());binding.efCopyInvitation.setEnabled(!model.isPending());}
        });
        binding.efCopyInvitation.setOnClickListener(ignored -> copyInvitation());
        binding.efInvitationCode.setOnClickListener(ignored -> {if(model.invitation.getValue()==null)model.loadDetails();});
        model.loadDetails();
        binding.efCreate.setOnClickListener(ignored -> { dismissKeyboard(); model.create(); });
        binding.efJoin.setOnClickListener(ignored -> { dismissKeyboard(); model.join(); });
        binding.efHouseholdClose.setOnClickListener(ignored -> close());
        binding.efLeave.setVisibility(host.sessions().currentHouseholdId() == null ? View.GONE : View.VISIBLE);
        binding.efLeave.setOnClickListener(ignored -> new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setMessage(R.string.ef_household_leave_confirm).setNegativeButton(R.string.ef_household_leave_cancel, null)
                .setPositiveButton(R.string.ef_household_leave_yes, (dialog, which) -> model.leave()).show());
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), new androidx.activity.OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { if (!model.isPending()) close(); }
        });
        model.left.observe(getViewLifecycleOwner(), value -> { if (Boolean.TRUE.equals(value) && isResumed()) close(); });
        binding.efAbandon.setOnClickListener(ignored -> model.abandon());
        model.recovering.observe(getViewLifecycleOwner(), value -> {
            boolean recovering = value != null && !value.isEmpty();
            binding.efAbandon.setVisibility(recovering && !model.joined ? View.VISIBLE : View.GONE);
            if (recovering && !model.joined) {
                binding.efHouseholdName.setText(model.name);
                binding.efHouseholdCode.setText(model.code);
                binding.efHouseholdMessage.setVisibility(View.VISIBLE);
                binding.efHouseholdMessage.setText(R.string.ef_recovered);
            }
        });
        model.loading.observe(getViewLifecycleOwner(), value -> {
            boolean busy = Boolean.TRUE.equals(value);
            binding.efCreate.setEnabled(!busy);
            binding.efJoin.setEnabled(!busy);
            binding.efLeave.setEnabled(!busy);
            binding.efSaveHouseholdName.setEnabled(model.canSaveName());
            binding.efEditHouseholdName.setEnabled(!busy);
            binding.efCopyInvitation.setEnabled(!busy && model.invitation.getValue()!=null);
            binding.efAbandon.setEnabled(!busy);
            binding.efHouseholdClose.setEnabled(!busy);
            binding.efCreate.setText(busy ? R.string.ef_busy : R.string.ef_create_household);
        });
        model.message.observe(getViewLifecycleOwner(), value -> {
            binding.efHouseholdMessage.setVisibility(value == null || value.isEmpty() ? View.GONE : View.VISIBLE);
            if (value != null && !value.isEmpty()) {
                binding.efHouseholdMessage.setText(messageResource(value));
                View message = binding.efHouseholdMessage;
                message.post(() -> { if (binding != null && binding.efHouseholdMessage == message) message.requestRectangleOnScreen(new android.graphics.Rect(0, 0, message.getWidth(), message.getHeight()), false); });
            }
        });
        model.ready.observe(getViewLifecycleOwner(), value -> {
            if (Boolean.TRUE.equals(value) && isResumed()) close();
        });
    }

    private void dismissKeyboard() {
        View focus = requireActivity().getCurrentFocus(); if (focus != null) focus.clearFocus();
        androidx.core.view.WindowCompat.getInsetsController(requireActivity().getWindow(), requireActivity().getWindow().getDecorView()).hide(androidx.core.view.WindowInsetsCompat.Type.ime());
    }

    private void close() {
        boolean profile = getArguments() != null && "PROFILE".equals(getArguments().getString("ef.household.return"));
        ((edu.xjtlu.civa.core.ui.NavHost) requireActivity()).navigate(
                profile ? edu.xjtlu.civa.core.ui.Route.PROFILE : edu.xjtlu.civa.core.ui.Route.TODAY, null);
    }

    @Override public void onResume() {
        super.onResume();
        if (model != null && Boolean.TRUE.equals(model.left.getValue())) { close(); return; }
        if (model != null && Boolean.TRUE.equals(model.ready.getValue())) close();
    }

    @Override public void onDestroyView() {
        binding = null;
        super.onDestroyView();
    }
    private void copyInvitation() {
        if(model==null||model.invitation.getValue()==null)return;
        String code=model.invitation.getValue().getCode();
        var clipboard=(android.content.ClipboardManager)requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        android.content.ClipData clip=android.content.ClipData.newPlainText(getString(R.string.ef_fixed_invitation_label),code);
        if(android.os.Build.VERSION.SDK_INT>=33){android.os.PersistableBundle extras=new android.os.PersistableBundle();extras.putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE,true);clip.getDescription().setExtras(extras);}
        clipboard.setPrimaryClip(clip);
        edu.xjtlu.civa.core.ui.Feedback.show(requireActivity(),R.string.ef_invitation_copied,false);
    }

    private static int messageResource(String code) {
        return switch (code) {
            case "VALIDATION_ERROR" -> R.string.ef_invalid_input;
            case "HOUSEHOLD_NAME_SAVED" -> R.string.ef_household_name_saved;
            case "HOUSEHOLD_NAME_CONFLICT" -> R.string.ef_household_name_conflict;
            case "VERSION_CONFLICT" -> R.string.ef_household_version_conflict;
            case "ALREADY_IN_HOUSEHOLD" -> R.string.ef_already_in_household;
            case "FORBIDDEN" -> R.string.ef_generic_error;
            case "RETRY_SAME_OPERATION" -> R.string.ef_retry_same_operation;
            case "INVALID_INVITATION" -> R.string.ef_invalid_invitation;
            case "LOCAL_STORAGE_ERROR" -> R.string.ef_generic_error;
            case "NETWORK_ERROR", "TIMEOUT" -> R.string.ef_network_error;
            default -> R.string.ef_generic_error;
        };
    }

    private static TextWatcher watcher(Consumer<String> change) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { change.accept(s.toString()); }
            @Override public void afterTextChanged(Editable s) { }
        };
    }
}
