package dev.ariqq.bounty.discord;

public interface BountyNotifier {
    void notifyBountyPlaced(String placerName, String targetName, long amount, long totalPool, boolean adminAction);

    void notifyBountyCancelled(String placerName, String targetName, long refundAmount);

    void notifyBountyClaimed(String killerName, String targetName, long totalAmount, int sourceCount);

    void notifyAdminTargetRemoved(String targetName, int removedContributions);

    void notifyAdminRefund(String targetName, long refundedAmount, int refundedContributions);
}
