package com.nexusuniverse.economy.credit;

import java.util.UUID;

public class CreditAccount {

    private final UUID playerId;
    private double creditLimit;
    private double balanceOwed;
    private double currentCycleMinimumDue;
    private double currentCyclePaid;
    private int creditScore;
    private int missedPayments;
    private boolean frozen;

    public CreditAccount(UUID playerId, double creditLimit, int creditScore) {
        this.playerId = playerId;
        this.creditLimit = creditLimit;
        this.creditScore = creditScore;
    }

    public UUID playerId() {
        return playerId;
    }

    public double creditLimit() {
        return creditLimit;
    }

    public void setCreditLimit(double limit) {
        this.creditLimit = limit;
    }

    public double balanceOwed() {
        return balanceOwed;
    }

    public void setBalanceOwed(double amount) {
        this.balanceOwed = Math.max(0, amount);
    }

    public double availableCredit() {
        return Math.max(0, creditLimit - balanceOwed);
    }

    public double currentCycleMinimumDue() {
        return currentCycleMinimumDue;
    }

    public void setCurrentCycleMinimumDue(double amount) {
        this.currentCycleMinimumDue = amount;
    }

    public double currentCyclePaid() {
        return currentCyclePaid;
    }

    public void addCyclePayment(double amount) {
        this.currentCyclePaid += amount;
    }

    public void resetCyclePaid() {
        this.currentCyclePaid = 0;
    }

    public int creditScore() {
        return creditScore;
    }

    public void setCreditScore(int score) {
        this.creditScore = score;
    }

    public int missedPayments() {
        return missedPayments;
    }

    public void setMissedPayments(int count) {
        this.missedPayments = Math.max(0, count);
    }

    public boolean isFrozen() {
        return frozen;
    }

    public void setFrozen(boolean frozen) {
        this.frozen = frozen;
    }
}
