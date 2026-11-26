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
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import edu.xjtlu.civa.api.model.Household;
import edu.xjtlu.civa.api.model.Membership;
import edu.xjtlu.civa.api.model.OwnershipTransferRequest;
import edu.xjtlu.civa.feature.ef.databinding.EfFragmentHouseholdBinding;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

public final class HouseholdFragment extends Fragment {
    private HouseholdViewModel model;
    private EfFragmentHouseholdBinding binding;
    private HouseholdHost host;

    public HouseholdFragment() { super(R.layout.ef_fragment_household); }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);
        HouseholdHost host = (HouseholdHost) requireActivity();
        this.host = host;
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
        model.household.observe(getViewLifecycleOwner(), value -> { paintOwner(); paintTransfer(); });
        model.members.observe(getViewLifecycleOwner(), value -> { paintOwner(); paintTransfer(); });
        model.transfer.observe(getViewLifecycleOwner(), value -> paintTransfer());
        model.leaveBlocked.observe(getViewLifecycleOwner(), code -> {
            if (code == null) return;
            model.leaveBlocked.setValue(null);
            handleLeaveBlocked(code);
        });
        binding.efTransferStart.setOnClickListener(ignored -> startTransfer());
        binding.efTransferAccept.setOnClickListener(ignored -> confirmAccept());
        binding.efTransferReject.setOnClickListener(ignored -> rejectTransfer());
        binding.efTransferCancel.setOnClickListener(ignored -> confirmCancel());
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), new androidx.activity.OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { if (!model.isPending()) close(); }
        });
        model.left.observe(getViewLifecycleOwner(), value -> { if (Boolean.TRUE.equals(value)) close(); });
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
            binding.efTransferStart.setEnabled(!busy);
            binding.efTransferAccept.setEnabled(!busy);
            binding.efTransferReject.setEnabled(!busy);
            binding.efTransferCancel.setEnabled(!busy);
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

    private void paintOwner() {
        if (binding == null) return;
        Household value = model.household.getValue();
        if (value == null) {
            binding.efHouseholdOwner.setText(R.string.ef_owner_loading);
            return;
        }
        if (value.getOwnerUserId() == null) {
            binding.efHouseholdOwner.setText(Household.OwnerSourceEnum.PENDING_CLAIM.equals(value.getOwnerSource())
                    ? R.string.ef_owner_pending_claim : R.string.ef_owner_unknown);
            return;
        }
        Membership owner = null;
        for (Membership member : model.activeMembers()) {
            if (value.getOwnerUserId().equals(member.getUserId())) { owner = member; break; }
        }
        String name = owner == null ? shortId(value.getOwnerUserId()) : displayName(owner);
        String currentUser = host.sessions() == null ? null : host.sessions().currentUserId();
        boolean self = currentUser != null && value.getOwnerUserId().toString().equals(currentUser);
        binding.efHouseholdOwner.setText(getString(self ? R.string.ef_owner_you : R.string.ef_owner_label, name));
    }

    private void paintTransfer() {
        if (binding == null) return;
        binding.efTransferStatus.setVisibility(View.GONE);
        binding.efTransferStart.setVisibility(View.GONE);
        binding.efTransferPendingActions.setVisibility(View.GONE);
        binding.efTransferAccept.setVisibility(View.GONE);
        binding.efTransferReject.setVisibility(View.GONE);
        binding.efTransferCancel.setVisibility(View.GONE);
        if (!model.joined) return;

        String currentUser = host.sessions() == null ? null : host.sessions().currentUserId();
        OwnershipTransferRequest request = model.transfer.getValue();
        if (request != null) {
            boolean owner = HouseholdTransferPolicy.isRequester(request, currentUser);
            boolean target = HouseholdTransferPolicy.isTarget(request, currentUser);
            if (owner) {
                setStatus(getString(R.string.ef_transfer_waiting, nameOf(request.getTargetUserId())));
                binding.efTransferPendingActions.setVisibility(View.VISIBLE);
                binding.efTransferCancel.setVisibility(View.VISIBLE);
            } else if (target) {
                setStatus(getString(R.string.ef_transfer_incoming, nameOf(request.getRequestedBy())));
                binding.efTransferPendingActions.setVisibility(View.VISIBLE);
                binding.efTransferAccept.setVisibility(View.VISIBLE);
                binding.efTransferReject.setVisibility(View.VISIBLE);
            }
            return;
        }

        Household value = model.household.getValue();
        if (value == null) return;
        if (value.getOwnerUserId() == null) {
            if (Household.OwnerSourceEnum.PENDING_CLAIM.equals(value.getOwnerSource())) {
                setStatus(getString(R.string.ef_transfer_pending_claim_note));
            }
            return;
        }
        if (model.isOwner() && !model.transferTargets().isEmpty()) {
            setStatus(getString(R.string.ef_transfer_hint));
            binding.efTransferStart.setVisibility(View.VISIBLE);
        }
    }

    private void setStatus(String text) {
        binding.efTransferStatus.setText(text);
        binding.efTransferStatus.setVisibility(View.VISIBLE);
    }

    private void startTransfer() {
        if (!model.isOwner()) {
            toast(R.string.ef_not_owner);
            return;
        }
        if (model.transfer.getValue() != null) {
            toast(R.string.ef_transfer_already_pending);
            return;
        }
        List<Membership> targets = model.transferTargets();
        if (targets.isEmpty()) {
            toast(R.string.ef_transfer_no_targets);
            return;
        }
        String[] names = new String[targets.size()];
        for (int index = 0; index < targets.size(); index++) names[index] = displayName(targets.get(index));
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.ef_transfer_pick_title)
                .setItems(names, (dialog, which) -> confirmStart(targets.get(which)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmStart(Membership target) {
        new MaterialAlertDialogBuilder(requireContext())
                .setMessage(R.string.ef_transfer_start_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.ef_transfer_start,
                        (dialog, which) -> model.requestTransfer(target.getUserId()))
                .show();
    }

    private void confirmAccept() {
        OwnershipTransferRequest request = model.transfer.getValue();
        if (request == null) return;
        new MaterialAlertDialogBuilder(requireContext())
                .setMessage(R.string.ef_transfer_accept_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.ef_transfer_accept,
                        (dialog, which) -> model.acceptTransfer(request.getId()))
                .show();
    }

    private void rejectTransfer() {
        OwnershipTransferRequest request = model.transfer.getValue();
        if (request != null) model.rejectTransfer(request.getId());
    }

    private void confirmCancel() {
        OwnershipTransferRequest request = model.transfer.getValue();
        if (request == null) return;
        new MaterialAlertDialogBuilder(requireContext())
                .setMessage(R.string.ef_transfer_cancel_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.ef_transfer_cancel,
                        (dialog, which) -> model.cancelTransfer(request.getId()))
                .show();
    }

    private void handleLeaveBlocked(String code) {
        OwnershipTransferRequest request = model.transfer.getValue();
        if ("OWNER_MUST_TRANSFER".equals(code)) {
            if (request != null) {
                new MaterialAlertDialogBuilder(requireContext())
                        .setMessage(R.string.ef_owner_leave_pending)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.ef_transfer_cancel,
                                (dialog, which) -> model.cancelTransfer(request.getId()))
                        .show();
            } else {
                new MaterialAlertDialogBuilder(requireContext())
                        .setMessage(R.string.ef_owner_leave_blocked)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.ef_owner_go_transfer,
                                (dialog, which) -> startTransfer())
                        .show();
            }
            return;
        }
        if ("OWNER_MUST_DISSOLVE".equals(code)) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setMessage(R.string.ef_owner_dissolve_confirm)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.ef_owner_dissolve_yes,
                            (dialog, which) -> model.dissolve())
                    .show();
            return;
        }
        toast(messageResource(code));
    }

    private String nameOf(UUID userId) {
        if (userId == null) return getString(R.string.ef_owner_unknown);
        for (Membership member : model.activeMembers()) {
            if (userId.equals(member.getUserId())) return displayName(member);
        }
        return shortId(userId);
    }

    private static String displayName(Membership member) {
        String name = member.getDisplayName();
        if (name != null && !name.isBlank()) return name;
        return member.getUsername() == null ? "" : member.getUsername();
    }

    private static String shortId(UUID id) {
        String text = id.toString();
        return text.length() > 8 ? text.substring(0, 8) : text;
    }

    private void toast(int text) {
        edu.xjtlu.civa.core.ui.Feedback.show(requireActivity(), text, false);
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
            case "TRANSFER_UPDATED" -> R.string.ef_transfer_completed;
            case "TRANSFER_ALREADY_PENDING" -> R.string.ef_transfer_already_pending;
            case "TRANSFER_NOT_PENDING" -> R.string.ef_transfer_not_pending;
            case "TRANSFER_REQUEST_NOT_FOUND" -> R.string.ef_transfer_missing;
            case "NOT_TRANSFER_TARGET" -> R.string.ef_transfer_not_target;
            case "INVALID_TRANSFER_TARGET" -> R.string.ef_invalid_transfer_target;
            case "NOT_HOUSEHOLD_OWNER" -> R.string.ef_not_owner;
            case "HOUSEHOLD_HAS_MEMBERS" -> R.string.ef_household_has_members;
            case "OWNER_MUST_TRANSFER" -> R.string.ef_owner_leave_blocked;
            case "OWNER_MUST_DISSOLVE" -> R.string.ef_owner_dissolve_confirm;
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
