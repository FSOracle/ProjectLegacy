package net.h2020.pit;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

/** Default Pit kit (2020 vanilla Pit): iron sword, bow, 32 arrows, chain chest+legs, iron boots. Unbreakable-ish. */
public final class PitKit {
    private PitKit() {}

    public static void give(Player p) {
        PlayerInventory inv = p.getInventory();
        inv.clear();
        inv.setItem(0, tag(new ItemStack(Material.IRON_SWORD)));
        inv.setItem(1, tag(new ItemStack(Material.BOW)));
        inv.setItem(8, tag(new ItemStack(Material.ARROW, 32)));
        inv.setHelmet(null);
        inv.setChestplate(tag(new ItemStack(Material.CHAINMAIL_CHESTPLATE)));
        inv.setLeggings(tag(new ItemStack(Material.CHAINMAIL_LEGGINGS)));
        inv.setBoots(tag(new ItemStack(Material.IRON_BOOTS)));
        inv.setHeldItemSlot(0);
        p.setHealth(p.getMaxHealth());
        p.setFoodLevel(20);
        p.setFireTicks(0);
        p.setFallDistance(0);
        p.updateInventory();
    }

    private static ItemStack tag(ItemStack it) {
        ItemMeta m = it.getItemMeta();
        m.spigot().setUnbreakable(true);
        it.setItemMeta(m);
        return it;
    }
}
