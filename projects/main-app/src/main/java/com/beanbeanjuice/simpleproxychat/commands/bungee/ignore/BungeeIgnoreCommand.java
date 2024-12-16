package com.beanbeanjuice.simpleproxychat.commands.bungee.ignore;

import com.beanbeanjuice.simpleproxychat.SimpleProxyChatBungee;
import com.beanbeanjuice.simpleproxychat.utility.config.Config;
import com.beanbeanjuice.simpleproxychat.utility.config.Permission;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Command;

public class BungeeIgnoreCommand extends Command {
    private final SimpleProxyChatBungee plugin;
    private final Config config;

    public BungeeIgnoreCommand(final SimpleProxyChatBungee plugin, String ... aliases) {
        super("Spc-ignore", Permission.COMMAND_IGNORE.getPermissionNode(), aliases);
        this.plugin = plugin;
        this.config = plugin.getConfig();
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        String playerName = args[0];
        ProxiedPlayer player = plugin.getProxy().getPlayer(playerName);
        if (player == null) {
            plugin.log("Ignore: player not found " + playerName);
            return;
        }
        plugin.log("TODO: ignore -> " + player.getDisplayName());
    }
}
