package edu.xjtlu.civa.core.data;

import edu.xjtlu.civa.api.model.AuthSession;
import edu.xjtlu.civa.api.model.LoginRequest;
import edu.xjtlu.civa.api.model.RegisterRequest;
import edu.xjtlu.civa.api.model.User;
import edu.xjtlu.civa.core.ui.ApiError;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import retrofit2.Call;

/** Authentication tokens remain inside core-data and are never passed to feature callbacks. */
public final class AuthRepository {
    private final RealApiProvider provider;
    private final SessionStore sessions;
    private final ApiCalls calls;
    private Call<AuthSession> pendingLogin;

    public AuthRepository(RealApiProvider provider, SessionStore sessions, ApiCalls calls) {
        this.provider = Objects.requireNonNull(provider);
        this.sessions = Objects.requireNonNull(sessions);
        this.calls = Objects.requireNonNull(calls);
        if (!provider.uses(sessions) || !calls.uses(sessions)) throw new IllegalArgumentException("Session store mismatch");
    }

    public void register(RegisterRequest request, ApiCallback<User> callback) {
        calls.enqueue(provider.auth().register(request), new ApiCallback<User>() {
            @Override public void onSuccess(User user) {
                if (validUser(user)) callback.onSuccess(user);
                else callback.onError(ApiCalls.localError("INVALID_RESPONSE"));
            }
            @Override public void onError(ApiError error) { callback.onError(error); }
        });
    }

    public void login(LoginRequest request, ApiCallback<User> callback) {
        synchronized (sessions) {
            long generation = sessions.snapshot().generation;
            if (pendingLogin != null) pendingLogin.cancel();
            Call<AuthSession> login = provider.auth().login(request);
            pendingLogin = login;
            calls.enqueue(login, new ApiCallback<AuthSession>() {
                @Override public void onSuccess(AuthSession result) {
                    synchronized (sessions) {
                        if (pendingLogin != login || sessions.snapshot().generation != generation) return;
                        pendingLogin = null;
                        if (!valid(result)) { callback.onError(ApiCalls.localError("INVALID_RESPONSE")); return; }
                        User user = result.getUser();
                        String household = user.getHouseholdId() == null ? null : user.getHouseholdId().toString();
                        long installed = sessions.installIfCurrent(generation, result.getToken(), user.getId().toString(), household);
                        if (installed >= 0 && sessions.snapshot().generation == installed) callback.onSuccess(user);
                    }
                }
                @Override public void onError(ApiError error) {
                    synchronized (sessions) {
                        if (pendingLogin != login || sessions.snapshot().generation != generation) return;
                        pendingLogin = null;
                        callback.onError(error);
                    }
                }
            });
        }
    }

    public void cancelLogin() {
        synchronized (sessions) {
            if (pendingLogin != null) pendingLogin.cancel();
            pendingLogin = null;
        }
    }

    /** Refreshes getMe and updates the session householdId after creating or joining a household. */
    public void refreshMe(ApiCallback<User> callback) {
        synchronized (sessions) {
            long generation = sessions.snapshot().generation;
            calls.enqueue(provider.profile().getMe(), new ApiCallback<User>() {
                @Override public void onSuccess(User user) {
                    synchronized (sessions) {
                        if (sessions.snapshot().generation != generation) return;
                        if (!validUser(user)) { callback.onError(ApiCalls.localError("INVALID_RESPONSE")); return; }
                        String household = user.getHouseholdId() == null ? null : user.getHouseholdId().toString();
                        if (sessions.updateHouseholdIfCurrent(generation, household)) callback.onSuccess(user);
                        else callback.onError(ApiCalls.localError("INVALID_RESPONSE"));
                    }
                }
                @Override public void onError(ApiError error) { callback.onError(error); }
            });
        }
    }

    public void logout(ApiCallback<Void> callback) {
        synchronized (sessions) {
            SessionStore.Snapshot old = sessions.snapshot();
            Call<Void> revoke = old.token == null ? null : provider.auth().logout();
            sessions.clear();
            long generation = sessions.snapshot().generation;
            if (revoke == null) calls.deliver(generation, () -> callback.onSuccess(null));
            else calls.enqueueDetached(revoke, generation, callback);
        }
    }

    /** Leave only the current user's membership; revoke household-scoped work after server confirmation. */
    public void leaveHousehold(ApiCallback<Void> callback) {
        synchronized (sessions) {
            SessionStore.Snapshot owner = sessions.snapshot();
            if (owner.token == null || owner.userId == null || owner.householdId == null) {
                callback.onError(ApiCalls.localError("NOT_FOUND")); return;
            }
            findMembership(owner, 0, callback);
        }
    }

    /** Close a single-member owned household; revoke household-scoped work after server confirmation. */
    public void dissolveHousehold(java.util.UUID key, ApiCallback<Void> callback) {
        synchronized (sessions) {
            SessionStore.Snapshot owner = sessions.snapshot();
            if (owner.token == null || owner.userId == null || owner.householdId == null) {
                callback.onError(ApiCalls.localError("NOT_FOUND")); return;
            }
            calls.enqueue(provider.household().dissolveHousehold(java.util.UUID.fromString(owner.householdId), key), new ApiCallback<Void>() {
                @Override public void onSuccess(Void value) {
                    synchronized (sessions) {
                        if (sessions.revokeHouseholdIfCurrent(owner.generation, owner.householdId)) callback.onSuccess(null);
                    }
                }
                @Override public void onError(ApiError error) { leaveFailed(owner, error, callback); }
            });
        }
    }

    private void findMembership(SessionStore.Snapshot owner, int page, ApiCallback<Void> callback) {
        calls.enqueue(provider.household().listMembers(java.util.UUID.fromString(owner.householdId), page, 100), new ApiCallback<edu.xjtlu.civa.api.model.MembershipPage>() {
            @Override public void onSuccess(edu.xjtlu.civa.api.model.MembershipPage result) {
                if (sessions.snapshot().generation != owner.generation) return;
                if (result == null || result.getItems() == null) { callback.onError(ApiCalls.localError("INVALID_RESPONSE")); return; }
                for (var member : result.getItems()) {
                    if (member != null && member.getUserId() != null && owner.userId.equals(member.getUserId().toString()) && Boolean.TRUE.equals(member.getActive())) {
                        if (member.getVersion() == null) { callback.onError(ApiCalls.localError("INVALID_RESPONSE")); return; }
                        removeMembership(owner, member.getVersion(), callback); return;
                    }
                }
                if (!result.getItems().isEmpty() && result.getTotal() != null && (page + 1L) * 100 < result.getTotal()) findMembership(owner, page + 1, callback);
                else callback.onError(ApiCalls.localError("NOT_FOUND"));
            }
            @Override public void onError(ApiError error) { leaveFailed(owner, error, callback); }
        });
    }

    private void removeMembership(SessionStore.Snapshot owner, long version, ApiCallback<Void> callback) {
        calls.enqueue(provider.household().removeMember(java.util.UUID.fromString(owner.householdId), java.util.UUID.fromString(owner.userId), version), new ApiCallback<Void>() {
            @Override public void onSuccess(Void value) {
                synchronized (sessions) {
                    if (sessions.revokeHouseholdIfCurrent(owner.generation, owner.householdId)) callback.onSuccess(null);
                }
            }
            @Override public void onError(ApiError error) { leaveFailed(owner, error, callback); }
        });
    }

    private void leaveFailed(SessionStore.Snapshot owner, ApiError error, ApiCallback<Void> callback) {
        SessionStore.Snapshot current = sessions.snapshot();
        if ("HOUSEHOLD_ACCESS_REVOKED".equals(error.code) && current.generation == owner.generation + 1
                && Objects.equals(current.userId, owner.userId) && current.householdId == null && current.token != null) {
            callback.onSuccess(null); return;
        }
        if (current.generation != owner.generation) return;
        if (!"NETWORK_ERROR".equals(error.code) && !"TIMEOUT".equals(error.code)) { callback.onError(error); return; }
        // A lost DELETE response is unknown, not a local-only successful exit. Reconcile with getMe.
        calls.enqueue(provider.profile().getMe(), new ApiCallback<User>() {
            @Override public void onSuccess(User user) {
                synchronized (sessions) {
                    if (validUser(user) && owner.userId.equals(user.getId().toString()) && user.getHouseholdId() == null
                            && sessions.revokeHouseholdIfCurrent(owner.generation, owner.householdId)) callback.onSuccess(null);
                    else callback.onError(error);
                }
            }
            @Override public void onError(ApiError ignored) { callback.onError(error); }
        });
    }

    private static boolean valid(AuthSession session) {
        if (session == null || session.getToken() == null || session.getToken().isEmpty() || !validUser(session.getUser())
                || session.getExpiresAt() == null) return false;
        try { Instant.parse(session.getExpiresAt()); return true; }
        catch (DateTimeParseException ex) { return false; }
    }

    private static boolean validUser(User user) {
        return user != null && user.getId() != null && user.getUsername() != null && user.getDisplayName() != null
                && user.getZoneId() != null && user.getCreatedAt() != null && user.getUpdatedAt() != null;
    }
}
