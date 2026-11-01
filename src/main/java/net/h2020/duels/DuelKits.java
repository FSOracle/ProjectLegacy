package net.h2020.duels;

import net.h2020.api.game.GameType;
import net.h2020.core.game.GameManagerImpl;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.potion.Potion;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;

/** Hypixel-style 2020 kits. Every give() starts from a full player reset. */
final class DuelKits {
    private DuelKits() {}

    static final int NODEBUFF_HEALS = 28;

    static void give(GameType type, Player p, int side) {
        GameManagerImpl.resetPlayer(p, GameMode.SURVIVAL);
        PlayerInventory inv = p.getInventory();
        switch (type) {
            case DUELS_CLASSIC: {
                inv.setItem(0, new ItemStack(Material.IRON_SWORD));
                inv.setItem(1, new ItemStack(Material.BOW));
                inv.setItem(2, new ItemStack(Material.FISHING_ROD));
                ItemStack flint = new ItemStack(Material.FLINT_AND_STEEL);
                flint.setDurability((short) (Material.FLINT_AND_STEEL.getMaxDurability() - 4)); // 4 uses
                inv.setItem(3, flint);
                inv.setItem(8, new ItemStack(Material.ARROW, 8));
                inv.setHelmet(new ItemStack(Material.IRON_HELMET));
                inv.setChestplate(new ItemStack(Material.IRON_CHESTPLATE));
                inv.setLeggings(new ItemStack(Material.IRON_LEGGINGS));
                inv.setBoots(new ItemStack(Material.IRON_BOOTS));
                break;
            }
            case DUELS_BOXING: {
                ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
                sword.addUnsafeEnchantment(Enchantment.DURABILITY, 10);
                inv.setItem(0, sword);
                p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, Integer.MAX_VALUE, 1, false, false));
                break;
            }
            case DUELS_NODEBUFF: {
                ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
                sword.addUnsafeEnchantment(Enchantment.DAMAGE_ALL, 2);
                sword.addUnsafeEnchantment(Enchantment.DURABILITY, 3);
                inv.setItem(0, sword);
                inv.setItem(1, new ItemStack(Material.ENDER_PEARL, 16));
                inv.setItem(2, new ItemStack(Material.COOKED_BEEF, 64));
                inv.setItem(3, new Potion(PotionType.SPEED, 2).toItemStack(1));
                inv.setItem(4, new Potion(PotionType.FIRE_RESISTANCE, 1).toItemStack(1));
                int placed = 0;
                for (int slot = 5; slot <= 35 && placed < NODEBUFF_HEALS; slot++) {
                    if (slot == 9 || slot == 18) inv.setItem(slot, new Potion(PotionType.SPEED, 2).toItemStack(1));
                    else { inv.setItem(slot, healSplash()); placed++; }
                }
                inv.setHelmet(diamond(Material.DIAMOND_HELMET));
                inv.setChestplate(diamond(Material.DIAMOND_CHESTPLATE));
                inv.setLeggings(diamond(Material.DIAMOND_LEGGINGS));
                inv.setBoots(diamond(Material.DIAMOND_BOOTS));
                break;
            }
            case DUELS_BRIDGE: {
                int clay = side == 0 ? 14 : 11;
                Color color = side == 0 ? Color.RED : Color.BLUE;
                inv.setItem(0, new ItemStack(Material.IRON_SWORD));
                inv.setItem(1, new ItemStack(Material.BOW));
                ItemStack pick = new ItemStack(Material.DIAMOND_PICKAXE);
                pick.addUnsafeEnchantment(Enchantment.DIG_SPEED, 2);
                inv.setItem(2, pick);
                inv.setItem(3, new ItemStack(Material.STAINED_CLAY, 64, (short) clay));
                inv.setItem(4, new ItemStack(Material.STAINED_CLAY, 64, (short) clay));
                inv.setItem(5, new ItemStack(Material.GOLDEN_APPLE, 8));
                inv.setItem(8, new ItemStack(Material.ARROW, 1));
                inv.setChestplate(leather(Material.LEATHER_CHESTPLATE, color));
                inv.setLeggings(leather(Material.LEATHER_LEGGINGS, color));
                inv.setBoots(leather(Material.LEATHER_BOOTS, color));
                break;
            }
            default: break;
        }
        inv.setHeldItemSlot(0);
        p.setHealth(p.getMaxHealth());
        p.setFoodLevel(20);
        p.setSaturation(20f);
        p.updateInventory();
    }

    static String describe(Player p) {
        StringBuilder sb = new StringBuilder("items=");
        for (ItemStack i : p.getInventory().getContents()) if (i != null) sb.append(i.getType()).append('x').append(i.getAmount()).append(',');
        sb.append(" armor=");
        for (ItemStack i : p.getInventory().getArmorContents()) sb.append(i == null || i.getType() == Material.AIR ? "none" : i.getType().name()).append(',');
        return sb.toString();
    }

    static ItemStack healSplash() {
        Potion pot = new Potion(PotionType.INSTANT_HEAL, 2);
        pot.setSplash(true);
        return pot.toItemStack(1);
    }

    static boolean isHealSplash(ItemStack i) {
        if (i == null || i.getType() != Material.POTION || i.getDurability() == 0) return false;
        try { Potion p = Potion.fromItemStack(i); return p.isSplash() && p.getType() == PotionType.INSTANT_HEAL; }
        catch (Throwable t) { return false; }
    }

    private static ItemStack diamond(Material m) {
        ItemStack i = new ItemStack(m);
        i.addUnsafeEnchantment(Enchantment.PROTECTION_ENVIRONMENTAL, 2);
        i.addUnsafeEnchantment(Enchantment.DURABILITY, 3);
        return i;
    }

    private static ItemStack leather(Material m, Color c) {
        ItemStack i = new ItemStack(m);
        LeatherArmorMeta meta = (LeatherArmorMeta) i.getItemMeta();
        meta.setColor(c);
        i.setItemMeta(meta);
        return i;
    }
}
