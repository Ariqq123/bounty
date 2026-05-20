package dev.ariqq.bounty.discord;

public final class NoOpBountyNotifier implements BountyNotifier {
    @Override
    public void notifyBountyPlaced(String placerName, String targetName, long amount, long totalPool, boolean adminAction) {
    }

    @Override
    public void notifyBountyCancelled(String placerName, String targetName, long refundAmount) {
    }

    @Override
    public void notifyBountyClaimed(String killerName, String targetName, long totalAmount, int sourceCount) {
    }

    @Override
    public void notifyAdminTargetRemoved(String targetName, int removedContributions) {
    }

    @Override
    public void notifyAdminRefund(String targetName, long refundedAmount, int refundedContributions) {
    }
}
