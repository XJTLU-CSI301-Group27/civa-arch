package edu.xjtlu.civa.feature.ef;

import edu.xjtlu.civa.api.model.Membership;
import edu.xjtlu.civa.api.model.OwnershipTransferRequest;
import java.util.List;
import java.util.UUID;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HouseholdTransferPolicyTest {
    private static final String ME = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER = "22222222-2222-2222-2222-222222222222";

    @Test public void targetsExcludeSelfAndInactiveMembers() {
        List<Membership> result = HouseholdTransferPolicy.targets(List.of(
                member(ME, true), member(OTHER, true), member(UUID.randomUUID().toString(), false)), ME);

        assertEquals(1, result.size());
        assertEquals(UUID.fromString(OTHER), result.get(0).getUserId());
    }

    @Test public void requestRequiresOwnerNoPendingAndAnEligibleTarget() {
        List<Membership> targets = List.of(member(OTHER, true));

        assertTrue(HouseholdTransferPolicy.canRequest(true, false, targets));
        assertFalse(HouseholdTransferPolicy.canRequest(false, false, targets));
        assertFalse(HouseholdTransferPolicy.canRequest(true, true, targets));
        assertFalse(HouseholdTransferPolicy.canRequest(true, false, List.of()));
    }

    @Test public void requesterAndTargetAreIdentifiedByUserId() {
        OwnershipTransferRequest request = new OwnershipTransferRequest()
                .requestedBy(UUID.fromString(ME)).targetUserId(UUID.fromString(OTHER));

        assertTrue(HouseholdTransferPolicy.isRequester(request, ME));
        assertFalse(HouseholdTransferPolicy.isRequester(request, OTHER));
        assertTrue(HouseholdTransferPolicy.isTarget(request, OTHER));
        assertFalse(HouseholdTransferPolicy.isTarget(request, ME));
        assertFalse(HouseholdTransferPolicy.isTarget(null, OTHER));
    }

    @Test public void sameKeyIsOnlyReusedForTheExactSameIntent() {
        assertTrue(HouseholdTransferPolicy.sameIntent(
                "requestOwnershipTransfer", OTHER, "requestOwnershipTransfer", OTHER));
        assertFalse(HouseholdTransferPolicy.sameIntent(
                "requestOwnershipTransfer", OTHER, "requestOwnershipTransfer", ME));
        assertFalse(HouseholdTransferPolicy.sameIntent(
                "requestOwnershipTransfer", OTHER, "acceptOwnershipTransfer", OTHER));
        assertFalse(HouseholdTransferPolicy.sameIntent(null, null, "requestOwnershipTransfer", OTHER));
    }

    private static Membership member(String userId, boolean active) {
        Membership member = new Membership();
        member.setUserId(UUID.fromString(userId));
        member.setActive(active);
        member.setDisplayName("Member " + userId.substring(0, 4));
        return member;
    }
}
