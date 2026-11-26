package edu.xjtlu.civa.feature.ef;

import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import edu.xjtlu.civa.api.model.CreateHouseholdRequest;
import edu.xjtlu.civa.api.model.CreateOwnershipTransferRequest;
import edu.xjtlu.civa.api.model.Household;
import edu.xjtlu.civa.api.model.Invitation;
import edu.xjtlu.civa.api.model.JoinHouseholdRequest;
import edu.xjtlu.civa.api.model.Membership;
import edu.xjtlu.civa.api.model.MembershipPage;
import edu.xjtlu.civa.api.model.OwnershipTransferRequest;
import edu.xjtlu.civa.api.model.OwnershipTransferRequestPage;
import edu.xjtlu.civa.api.model.TransferStatus;
import edu.xjtlu.civa.api.model.User;
import edu.xjtlu.civa.core.data.*;
import edu.xjtlu.civa.core.ui.ApiError;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import retrofit2.Call;

/** Setup and joined-family settings share navigation but never mix their controls. */
final class HouseholdViewModel extends ViewModel {
    final MutableLiveData<Boolean> loading=new MutableLiveData<>(false);
    final MutableLiveData<Boolean> ready=new MutableLiveData<>(false);
    final MutableLiveData<Boolean> left=new MutableLiveData<>(false);
    final MutableLiveData<String> message=new MutableLiveData<>();
    final MutableLiveData<String> recovering=new MutableLiveData<>();
    final MutableLiveData<String> householdName=new MutableLiveData<>();
    final MutableLiveData<Invitation> invitation=new MutableLiveData<>();
    final MutableLiveData<Household> household=new MutableLiveData<>();
    final MutableLiveData<List<Membership>> members=new MutableLiveData<>(List.of());
    final MutableLiveData<OwnershipTransferRequest> transfer=new MutableLiveData<>();
    final MutableLiveData<String> leaveBlocked=new MutableLiveData<>();
    final MutableLiveData<String> transferRecovery=new MutableLiveData<>();
    final boolean joined;
    private final ApiProvider api;private final ApiCalls calls;private final AuthRepository auth;private final SessionStore sessions;
    private final String user,home;
    String name="",code="",editedName="";
    private Long householdVersion;
    private boolean nameEdited;
    private boolean closed,pending,detailsLoading;
    private String pendingKey,pendingOperation,pendingPayload;
    private PendingWrite recoveredWrite;
    private int householdGeneration;
    HouseholdViewModel(ApiProvider api,ApiCalls calls,AuthRepository auth,SessionStore sessions) {
        this.api=api;this.calls=calls;this.auth=auth;this.sessions=sessions;
        user=sessions.currentUserId();home=sessions.currentHouseholdId();joined=home!=null;
    }
    private boolean current(){return !closed&&Objects.equals(user,sessions.currentUserId())&&Objects.equals(home,sessions.currentHouseholdId());}
    private UUID homeId(){return UUID.fromString(home);}

    void loadDetails() {
        loadHousehold();
        loadInvitation();
    }

    private void loadHousehold() {
        if(!joined||!current())return;
        int generation=++householdGeneration;
        calls.enqueue(api.household().getHousehold(homeId()),new ApiCallback<Household>() {
            @Override public void onSuccess(Household value){
                if(generation!=householdGeneration||!current()||value==null)return;
                householdVersion=value.getVersion();household.setValue(value);
                if(!nameEdited)editedName=value.getName();
                householdName.setValue(value.getName());
                loadMembers(generation);
                loadTransfer(generation);
            }
            @Override public void onError(ApiError error){if(generation==householdGeneration&&current())message.setValue(error.code);}
        });
    }

    private void loadInvitation() {
        if(!joined||!current()||detailsLoading)return;
        detailsLoading=true;
        calls.enqueue(api.household().getHouseholdInvitation(homeId()),new ApiCallback<Invitation>() {
            @Override public void onSuccess(Invitation value){
                if(!current())return;detailsLoading=false;
                if(value==null||value.getCode()==null||!home.equals(value.getHouseholdId().toString())){message.setValue("INVALID_RESPONSE");return;}
                invitation.setValue(value);
            }
            @Override public void onError(ApiError error){if(current()){detailsLoading=false;message.setValue(error.code);}}
        });
    }

    private void loadMembers(int generation) {
        calls.enqueue(api.household().listMembers(homeId(),0,50),new ApiCallback<MembershipPage>() {
            @Override public void onSuccess(MembershipPage value){
                if(generation==householdGeneration&&current()&&value!=null&&value.getItems()!=null)members.setValue(value.getItems());
            }
            @Override public void onError(ApiError error){ }
        });
    }

    private void loadTransfer(int generation) {
        calls.enqueue(api.household().listOwnershipTransferRequests(homeId(),TransferStatus.PENDING,0,10),
                new ApiCallback<OwnershipTransferRequestPage>() {
            @Override public void onSuccess(OwnershipTransferRequestPage value){
                if(generation!=householdGeneration||!current())return;
                transfer.setValue(value==null||value.getItems()==null||value.getItems().isEmpty()?null:value.getItems().get(0));
            }
            @Override public void onError(ApiError error){
                if(generation==householdGeneration&&current())transfer.setValue(null);
            }
        });
    }

    boolean isOwner() {
        Household value=household.getValue();
        String currentUser=sessions.currentUserId();
        return value!=null&&value.getOwnerUserId()!=null&&currentUser!=null
                &&value.getOwnerUserId().toString().equals(currentUser);
    }

    List<Membership> activeMembers() {
        List<Membership> value=members.getValue();
        if(value==null)return List.of();
        List<Membership> result=new ArrayList<>();
        for(Membership member:value)if(member!=null&&Boolean.TRUE.equals(member.getActive()))result.add(member);
        return result;
    }

    List<Membership> transferTargets() {
        return HouseholdTransferPolicy.targets(activeMembers(), user);
    }

    void requestTransfer(UUID target) {
        if(!isOwner()){message.setValue("NOT_HOUSEHOLD_OWNER");return;}
        if(transfer.getValue()!=null){message.setValue("TRANSFER_ALREADY_PENDING");return;}
        if(target==null||target.toString().equals(user)||transferTargets().stream().noneMatch(m->target.equals(m.getUserId()))){
            message.setValue("INVALID_TRANSFER_TARGET");return;
        }
        withIntent("requestOwnershipTransfer",target.toString(),()->calls.enqueue(
                api.household().requestOwnershipTransfer(homeId(),UUID.fromString(pendingKey),
                        new CreateOwnershipTransferRequest().targetUserId(target)),transferCallback(false)));
    }

    void acceptTransfer(UUID requestId) {
        OwnershipTransferRequest value=transfer.getValue();
        if(value==null||!requestId.equals(value.getId())){message.setValue("TRANSFER_NOT_PENDING");return;}
        if(value.getTargetUserId()==null||!value.getTargetUserId().toString().equals(user)){message.setValue("NOT_TRANSFER_TARGET");return;}
        withIntent("acceptOwnershipTransfer",requestId.toString(),()->calls.enqueue(
                api.household().acceptOwnershipTransfer(homeId(),requestId,UUID.fromString(pendingKey)),
                transferCallback(false)));
    }

    void rejectTransfer(UUID requestId) {
        OwnershipTransferRequest value=transfer.getValue();
        if(value==null||!requestId.equals(value.getId())){message.setValue("TRANSFER_NOT_PENDING");return;}
        if(value.getTargetUserId()==null||!value.getTargetUserId().toString().equals(user)){message.setValue("NOT_TRANSFER_TARGET");return;}
        withIntent("rejectOwnershipTransfer",requestId.toString(),()->calls.enqueue(
                api.household().rejectOwnershipTransfer(homeId(),requestId,UUID.fromString(pendingKey)),
                transferCallback(false)));
    }

    void cancelTransfer(UUID requestId) {
        OwnershipTransferRequest value=transfer.getValue();
        if(!isOwner()){message.setValue("NOT_HOUSEHOLD_OWNER");return;}
        if(value==null||!requestId.equals(value.getId())){message.setValue("TRANSFER_NOT_PENDING");return;}
        withIntent("cancelOwnershipTransfer",requestId.toString(),()->calls.enqueue(
                api.household().cancelOwnershipTransfer(homeId(),requestId,UUID.fromString(pendingKey)),
                transferCallback(false)));
    }

    void dissolve() {
        if(!isOwner()){message.setValue("NOT_HOUSEHOLD_OWNER");return;}
        withIntent("dissolveHousehold","dissolve",()->auth.dissolveHousehold(UUID.fromString(pendingKey),transferCallback(true)));
    }

    private void withIntent(String operation,String payload,Runnable send) {
        if(pending||!current())return;
        if(pendingKey!=null&&!HouseholdTransferPolicy.sameIntent(pendingOperation,pendingPayload,operation,payload)){
            message.setValue("RETRY_SAME_OPERATION");return;
        }
        pending=true;loading.setValue(true);message.setValue("");
        if(pendingKey!=null){send.run();return;}
        pendingKey=UUID.randomUUID().toString();pendingOperation=operation;pendingPayload=payload;
        calls.pendingWrites().record(pendingKey,user,home,operation,payload,null,null,new ApiCallback<Void>() {
            @Override public void onSuccess(Void ignored){if(current())send.run();}
            @Override public void onError(ApiError error){if(current()){pendingKey=null;pendingOperation=null;pendingPayload=null;finishError(error.code);}}
        });
    }

    private <T> ApiCallback<T> transferCallback(boolean close) {
        return new ApiCallback<T>() {
            @Override public void onSuccess(T value){if(close||current())confirmTransfer(close);}
            @Override public void onError(ApiError error){if(current())transferFailed(error);}
        };
    }

    private void confirmTransfer(boolean close) {
        String key=pendingKey;
        calls.pendingWrites().confirm(user,key,new ApiCallback<Void>() {
            @Override public void onSuccess(Void value){finishTransfer(close);}
            @Override public void onError(ApiError error){finishTransfer(close);}
        });
    }

    private void finishTransfer(boolean close) {
        pendingKey=null;pendingOperation=null;pendingPayload=null;recoveredWrite=null;
        pending=false;loading.setValue(false);recovering.setValue(null);transferRecovery.setValue(null);
        if(close){
            // Dissolve revokes the session household before this callback, so the page must close
            // based on the still-signed-in user rather than the now-null household id.
            if(!closed&&Objects.equals(user,sessions.currentUserId()))left.setValue(true);
            return;
        }
        if(!current())return;
        message.setValue("TRANSFER_UPDATED");
        loadHousehold();
    }

    private void transferFailed(ApiError error) {
        if(isUncertain(error.code)){pending=false;loading.setValue(false);message.setValue(error.code);return;}
        String key=pendingKey;
        calls.pendingWrites().confirm(user,key,new ApiCallback<Void>() {
            @Override public void onSuccess(Void value){finishTransferError(error.code);}
            @Override public void onError(ApiError storage){finishTransferError(error.code);}
        });
    }

    private void finishTransferError(String code) {
        if(!current())return;
        pendingKey=null;pendingOperation=null;pendingPayload=null;recoveredWrite=null;transferRecovery.setValue(null);
        pending=false;loading.setValue(false);
        message.setValue(code);
        loadHousehold();
    }

    private static boolean isUncertain(String code) {
        return "NETWORK_ERROR".equals(code)||"TIMEOUT".equals(code)||"TEMPORARILY_UNAVAILABLE".equals(code);
    }

    private static boolean isTransferOperation(String operation) {
        return "requestOwnershipTransfer".equals(operation)||"acceptOwnershipTransfer".equals(operation)
                ||"rejectOwnershipTransfer".equals(operation)||"cancelOwnershipTransfer".equals(operation)
                ||"dissolveHousehold".equals(operation);
    }

    void editName(String value) { editedName=value;nameEdited=true; }
    boolean canSaveName() { return joined&&householdVersion!=null&&!pending&&current(); }
    void saveName() {
        if(!canSaveName())return;
        String desired=editedName==null?"":editedName.trim();
        if(desired.isEmpty()||desired.codePointCount(0,desired.length())>120){message.setValue("VALIDATION_ERROR");return;}
        long version=householdVersion;pending=true;loading.setValue(true);message.setValue("");
        calls.enqueue(api.household().renameHousehold(homeId(),version,new CreateHouseholdRequest().name(desired)),new ApiCallback<Household>() {
            @Override public void onSuccess(Household value){if(current())renamed(value);}
            @Override public void onError(ApiError error){
                if(!current())return;
                if("VERSION_CONFLICT".equals(error.code)||isUncertain(error.code))reconcileName(desired,error.code);
                else finishError(error.code);
            }
        });
    }
    private void renamed(Household value) {
        if(value==null||value.getName()==null||value.getVersion()==null){finishError("INVALID_RESPONSE");return;}
        householdVersion=value.getVersion();editedName=value.getName();nameEdited=false;pending=false;
        household.setValue(value);
        householdName.setValue(value.getName());loading.setValue(false);message.setValue("HOUSEHOLD_NAME_SAVED");
    }
    private void reconcileName(String desired,String originalError) {
        calls.enqueue(api.household().getHousehold(homeId()),new ApiCallback<Household>() {
            @Override public void onSuccess(Household value){
                if(!current())return;
                if(value!=null&&desired.equals(value.getName())){renamed(value);return;}
                if(value!=null&&value.getVersion()!=null)householdVersion=value.getVersion();
                finishError("VERSION_CONFLICT".equals(originalError)?"HOUSEHOLD_NAME_CONFLICT":originalError);
            }
            @Override public void onError(ApiError error){if(current())finishError(originalError);}
        });
    }
    void recover() {
        if(user==null||pendingKey!=null)return;
        calls.pendingWrites().listPending(user,new ApiCallback<List<PendingWrite>>() {
            @Override public void onSuccess(List<PendingWrite> writes) {
                if(!current()||pending)return;
                for(PendingWrite write:writes) {
                    if(isTransferOperation(write.operationId)) {
                        if(!joined||!Objects.equals(home,write.householdId)){forget(write.key);continue;}
                        pendingKey=write.key;pendingOperation=write.operationId;pendingPayload=write.canonicalPayload;
                        recoveredWrite=write;
                        // Never replay a historical write automatically: the request may already have
                        // changed ownership or been rejected. Show the current state and let the user
                        // explicitly continue with the original key (or discard it).
                        transferRecovery.setValue(write.operationId);
                        return;
                    }
                    boolean setup="createHousehold".equals(write.operationId)||"joinHousehold".equals(write.operationId);
                    if("createInvitation".equals(write.operationId)||(joined&&setup)) {forget(write.key);continue;}
                    if(!joined&&setup) {
                        pendingKey=write.key;pendingOperation=write.operationId;
                        if("createHousehold".equals(pendingOperation))name=write.canonicalPayload;else code=write.canonicalPayload;
                        recovering.setValue(pendingOperation);return;
                    }
                }
            }
            @Override public void onError(ApiError error){if(current())message.setValue(error.code);}
        });
    }

    void continueRecoveredTransfer() {
        if(recoveredWrite==null||pending||!current())return;
        PendingWrite write=recoveredWrite;
        recoveredWrite=null;transferRecovery.setValue(null);
        retry(write);
    }

    void abandonRecoveredTransfer() {
        if(recoveredWrite==null)return;
        String key=recoveredWrite.key;
        recoveredWrite=null;transferRecovery.setValue(null);
        pendingKey=null;pendingOperation=null;pendingPayload=null;
        forget(key);
        message.setValue("TRANSFER_RECOVERY_DISCARDED");
    }

    private void retry(PendingWrite write) {
        pending=true;loading.setValue(true);
        String payload=write.canonicalPayload;
        pendingPayload=payload;
        switch(write.operationId) {
            case "requestOwnershipTransfer" -> calls.enqueue(api.household().requestOwnershipTransfer(homeId(),
                    UUID.fromString(pendingKey),new CreateOwnershipTransferRequest().targetUserId(UUID.fromString(payload))),
                    transferCallback(false));
            case "acceptOwnershipTransfer" -> calls.enqueue(api.household().acceptOwnershipTransfer(homeId(),
                    UUID.fromString(payload),UUID.fromString(pendingKey)),transferCallback(false));
            case "rejectOwnershipTransfer" -> calls.enqueue(api.household().rejectOwnershipTransfer(homeId(),
                    UUID.fromString(payload),UUID.fromString(pendingKey)),transferCallback(false));
            case "cancelOwnershipTransfer" -> calls.enqueue(api.household().cancelOwnershipTransfer(homeId(),
                    UUID.fromString(payload),UUID.fromString(pendingKey)),transferCallback(false));
            case "dissolveHousehold" -> auth.dissolveHousehold(UUID.fromString(pendingKey),transferCallback(true));
            default -> { pending=false;loading.setValue(false); }
        }
    }

    void create() {
        if(joined)return;
        String value=name==null?"":name.trim();if(value.isEmpty()){message.setValue("VALIDATION_ERROR");return;}
        submit("createHousehold",value,()->api.household().createHousehold(UUID.fromString(pendingKey),new CreateHouseholdRequest().name(value)));
    }
    void join() {
        if(joined)return;
        String value=code==null?"":code.trim();if(value.isEmpty()){message.setValue("VALIDATION_ERROR");return;}
        submit("joinHousehold",value,()->api.household().joinHousehold(UUID.fromString(pendingKey),new JoinHouseholdRequest().code(value)));
    }
    private <T> void submit(String operation,String payload,Supplier<Call<T>> request) {
        if(pending||!current())return;
        if(pendingKey!=null&&!operation.equals(pendingOperation)){message.setValue("RETRY_SAME_OPERATION");return;}
        pending=true;loading.setValue(true);message.setValue("");
        if(pendingKey!=null){dispatch(request);return;}
        pendingKey=UUID.randomUUID().toString();pendingOperation=operation;
        calls.pendingWrites().record(pendingKey,user,null,operation,payload,null,null,new ApiCallback<Void>() {
            @Override public void onSuccess(Void ignored){if(current())dispatch(request);}
            @Override public void onError(ApiError error){if(current()){pendingKey=null;pendingOperation=null;finishError(error.code);}}
        });
    }
    private <T> void dispatch(Supplier<Call<T>> request) {
        calls.enqueue(request.get(),new ApiCallback<T>() {
            @Override public void onSuccess(T value){if(current())confirmThenRefresh();}
            @Override public void onError(ApiError error){if(current())failed(error);}
        });
    }
    private void confirmThenRefresh() {
        String key=pendingKey;
        calls.pendingWrites().confirm(user,key,new ApiCallback<Void>() {
            @Override public void onSuccess(Void value){refresh();}
            @Override public void onError(ApiError error){refresh();}
            private void refresh(){
                if(!current())return;pendingKey=null;pendingOperation=null;recovering.setValue(null);
                auth.refreshMe(new ApiCallback<User>() {
                    @Override public void onSuccess(User value){if(!closed&&Objects.equals(user,sessions.currentUserId())){pending=false;loading.setValue(false);ready.setValue(true);}}
                    @Override public void onError(ApiError error){if(!closed){finishError(error.code);}}
                });
            }
        });
    }
    private void failed(ApiError error) {
        if(isUncertain(error.code)) {
            recovering.setValue(pendingOperation);finishError(error.code);return;
        }
        String key=pendingKey;
        calls.pendingWrites().confirm(user,key,new ApiCallback<Void>() {
            @Override public void onSuccess(Void value){if(current()){pendingKey=null;pendingOperation=null;recovering.setValue(null);finishError(error.code);}}
            @Override public void onError(ApiError storage){if(current())finishError(storage.code);}
        });
    }
    void abandon() {
        if(pending||pendingKey==null||!current())return;
        calls.pendingWrites().confirm(user,pendingKey,new ApiCallback<Void>() {
            @Override public void onSuccess(Void value){if(current()){pendingKey=null;pendingOperation=null;recovering.setValue(null);message.setValue("");}}
            @Override public void onError(ApiError error){if(current())message.setValue(error.code);}
        });
    }
    private void forget(String key){calls.pendingWrites().confirm(user,key,new ApiCallback<Void>() {
        @Override public void onSuccess(Void value){}
        @Override public void onError(ApiError error){if(current())message.setValue(error.code);}
    });}
    void leave() {
        if(pending||!joined||!current())return;pending=true;loading.setValue(true);message.setValue("");
        auth.leaveHousehold(new ApiCallback<Void>() {
            @Override public void onSuccess(Void value){if(!closed&&Objects.equals(user,sessions.currentUserId())){pending=false;loading.setValue(false);left.setValue(true);}}
            @Override public void onError(ApiError error){
                if(closed||!Objects.equals(user,sessions.currentUserId()))return;
                pending=false;loading.setValue(false);
                if("OWNER_MUST_TRANSFER".equals(error.code)||"OWNER_MUST_DISSOLVE".equals(error.code))leaveBlocked.setValue(error.code);
                else message.setValue(error.code);
            }
        });
    }
    private void finishError(String error){pending=false;loading.setValue(false);message.setValue(error);}
    boolean isPending(){return pending;}
    @Override protected void onCleared(){closed=true;}
}
