package edu.xjtlu.civa.feature.ef;

import edu.xjtlu.civa.api.model.Membership;
import edu.xjtlu.civa.api.model.OwnershipTransferRequest;
import java.util.ArrayList;
import java.util.List;

/** Pure ownership-transfer rules shared by the view model and the screen. */
public final class HouseholdTransferPolicy {
    private HouseholdTransferPolicy() { }

    /** Active members other than the current user, in member-list order. */
    public static List<Membership> targets(List<Membership> members, String currentUserId) {
        List<Membership> result = new ArrayList<>();
        if (members == null) return result;
        for (Membership member : members) {
            if (member == null || !Boolean.TRUE.equals(member.getActive())) continue;
            if (member.getUserId() == null) continue;
            if (currentUserId != null && currentUserId.equals(member.getUserId().toString())) continue;
            result.add(member);
        }
        return result;
    }

    public static boolean canRequest(boolean owner, boolean hasPending, List<Membership> targets) {
        return owner && !hasPending && targets != null && !targets.isEmpty();
    }

    public static boolean isRequester(OwnershipTransferRequest request, String currentUserId) {
        return request != null && request.getRequestedBy() != null && currentUserId != null
                && currentUserId.equals(request.getRequestedBy().toString());
    }

    public static boolean isTarget(OwnershipTransferRequest request, String currentUserId) {
        return request != null && request.getTargetUserId() != null && currentUserId != null
                && currentUserId.equals(request.getTargetUserId().toString());
    }

    /** A pending idempotency key may only be reused for the exact same operation and payload. */
    public static boolean sameIntent(String pendingOperation, String pendingPayload,
                                     String operation, String payload) {
        return pendingOperation != null && pendingPayload != null
                && pendingOperation.equals(operation) && pendingPayload.equals(payload);
    }
}
