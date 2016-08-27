package me.dinosparkour.utils;

import net.dv8tion.jda.entities.Guild;
import net.dv8tion.jda.entities.Message;
import net.dv8tion.jda.entities.Role;
import net.dv8tion.jda.entities.TextChannel;

import java.util.*;

public class RoleUtil {

    public List<Role> getMentionedRoles(Message msg, String allArgs) {
        if (msg.isPrivate()) // This method should ONLY be called for Guild messages
            return Collections.emptyList();

        Set<Role> results = new HashSet<>(msg.getMentionedRoles());
        Guild guild = ((TextChannel) msg.getChannel()).getGuild();
        Role idRole = guild.getRoleById(allArgs);
        if (idRole != null) // Passed arguments were a role id
            results.add(idRole);
        else // Get all roles with matching names
            guild.getRoles().stream()
                    .filter(role -> role.getName().equalsIgnoreCase(allArgs))
                    .forEach(results::add);
        return new ArrayList<>(results);
    }
}