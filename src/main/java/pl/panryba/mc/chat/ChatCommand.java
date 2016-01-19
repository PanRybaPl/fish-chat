/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package pl.panryba.mc.chat;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

/**
 *
 * @author PanRyba.pl
 */
public class ChatCommand implements CommandExecutor {

    private final Plugin plugin;
    
    public ChatCommand(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if(args.length == 0) {
            return false;
        }
        
        if(args[0].equalsIgnoreCase("on")) {
            this.plugin.enableChat();
            sender.sendMessage(ChatColor.GRAY + "Czat zostal wlaczony");
        } else if(args[0].equalsIgnoreCase("off")) {
            this.plugin.disableChat();
            sender.sendMessage(ChatColor.GRAY + "Czat zostal wylaczony");
        }
        
        return true;
    }
    
}
