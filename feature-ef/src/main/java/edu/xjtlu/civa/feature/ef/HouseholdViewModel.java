package edu.xjtlu.civa.feature.ef;

import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import edu.xjtlu.civa.api.model.CreateHouseholdRequest;
import edu.xjtlu.civa.api.model.JoinHouseholdRequest;
import edu.xjtlu.civa.api.model.Invitation;
import edu.xjtlu.civa.api.model.User;
import edu.xjtlu.civa.core.data.*;
import edu.xjtlu.civa.core.ui.ApiError;
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
    final boolean joined;
    private final ApiProvider api;private final ApiCalls calls;private final AuthRepository auth;private final SessionStore sessions;
    private final String user,home;
    String name="",code="",editedName="";
    private Long householdVersion;
    private boolean nameEdited;
    private boolean closed,pending,detailsLoading;
    private String pendingKey,pendingOperation;
    HouseholdViewModel(ApiProvider api,ApiCalls calls,AuthRepository auth,SessionStore sessions) {
        this.api=api;this.calls=calls;this.auth=auth;this.sessions=sessions;
        user=sessions.currentUserId();home=sessions.currentHouseholdId();joined=home!=null;
    }
    private boolean current(){return !closed&&Objects.equals(user,sessions.currentUserId())&&Objects.equals(home,sessions.currentHouseholdId());}
    void loadDetails() {
        if(!joined||!current()||detailsLoading)return;
        detailsLoading=true;
        calls.enqueue(api.household().getHousehold(UUID.fromString(home)),new ApiCallback<edu.xjtlu.civa.api.model.Household>() {
            @Override public void onSuccess(edu.xjtlu.civa.api.model.Household value){
                if(current()&&value!=null){householdVersion=value.getVersion();if(!nameEdited)editedName=value.getName();householdName.setValue(value.getName());}
            }
            @Override public void onError(ApiError error){if(current())message.setValue(error.code);}
        });
        calls.enqueue(api.household().getHouseholdInvitation(UUID.fromString(home)),new ApiCallback<Invitation>() {
            @Override public void onSuccess(Invitation value){
                if(!current())return;detailsLoading=false;
                if(value==null||value.getCode()==null||!home.equals(value.getHouseholdId().toString())){message.setValue("INVALID_RESPONSE");return;}
                invitation.setValue(value);
            }
            @Override public void onError(ApiError error){if(current()){detailsLoading=false;message.setValue(error.code);}}
        });
    }
    void editName(String value) { editedName=value;nameEdited=true; }
    boolean canSaveName() { return joined&&householdVersion!=null&&!pending&&current(); }
    void saveName() {
        if(!canSaveName())return;
        String desired=editedName==null?"":editedName.trim();
        if(desired.isEmpty()||desired.codePointCount(0,desired.length())>120){message.setValue("VALIDATION_ERROR");return;}
        long version=householdVersion;pending=true;loading.setValue(true);message.setValue("");
        calls.enqueue(api.household().renameHousehold(UUID.fromString(home),version,new CreateHouseholdRequest().name(desired)),new ApiCallback<edu.xjtlu.civa.api.model.Household>() {
            @Override public void onSuccess(edu.xjtlu.civa.api.model.Household value){if(current())renamed(value);}
            @Override public void onError(ApiError error){
                if(!current())return;
                if("VERSION_CONFLICT".equals(error.code)||"NETWORK_ERROR".equals(error.code)||"TIMEOUT".equals(error.code)||"TEMPORARILY_UNAVAILABLE".equals(error.code))reconcileName(desired,error.code);
                else finishError(error.code);
            }
        });
    }
    private void renamed(edu.xjtlu.civa.api.model.Household value) {
        if(value==null||value.getName()==null||value.getVersion()==null){finishError("INVALID_RESPONSE");return;}
        householdVersion=value.getVersion();editedName=value.getName();nameEdited=false;pending=false;
        householdName.setValue(value.getName());loading.setValue(false);message.setValue("HOUSEHOLD_NAME_SAVED");
    }
    private void reconcileName(String desired,String originalError) {
        calls.enqueue(api.household().getHousehold(UUID.fromString(home)),new ApiCallback<edu.xjtlu.civa.api.model.Household>() {
            @Override public void onSuccess(edu.xjtlu.civa.api.model.Household value){
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
        if("NETWORK_ERROR".equals(error.code)||"TIMEOUT".equals(error.code)||"TEMPORARILY_UNAVAILABLE".equals(error.code)) {
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
            @Override public void onError(ApiError error){if(!closed)finishError(error.code);}
        });
    }
    private void finishError(String error){pending=false;loading.setValue(false);message.setValue(error);}
    boolean isPending(){return pending;}
    @Override protected void onCleared(){closed=true;}
}
