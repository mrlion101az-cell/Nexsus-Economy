package com.nexusuniverse.economy.orders;

import org.bukkit.Material;

import java.util.UUID;

public class Order {

    private final UUID id;
    private final UUID posterId;
    private final String posterName;
    private final Material material;
    private int remainingQuantity;
    private final double payPerItem;

    public Order(UUID id, UUID posterId, String posterName, Material material, int remainingQuantity, double payPerItem) {
        this.id = id;
        this.posterId = posterId;
        this.posterName = posterName;
        this.material = material;
        this.remainingQuantity = remainingQuantity;
        this.payPerItem = payPerItem;
    }

    public UUID id() {
        return id;
    }

    public UUID posterId() {
        return posterId;
    }

    public String posterName() {
        return posterName;
    }

    public Material material() {
        return material;
    }

    public int remainingQuantity() {
        return remainingQuantity;
    }

    public double payPerItem() {
        return payPerItem;
    }

    public void reduce(int amount) {
        remainingQuantity -= amount;
    }
}
