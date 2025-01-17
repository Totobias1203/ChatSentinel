package dev._2lstudios.chatsentinel.bukkit.listeners;

import java.util.Collection;
import java.util.UUID;
import java.util.regex.Pattern;

import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import dev._2lstudios.chatsentinel.bukkit.ChatSentinel;
import dev._2lstudios.chatsentinel.bukkit.modules.ModuleManager;
import dev._2lstudios.chatsentinel.shared.chat.ChatPlayer;
import dev._2lstudios.chatsentinel.shared.chat.ChatPlayerManager;
import dev._2lstudios.chatsentinel.shared.interfaces.Module;
import dev._2lstudios.chatsentinel.shared.modules.BlacklistModule;
import dev._2lstudios.chatsentinel.shared.modules.CapsModule;
import dev._2lstudios.chatsentinel.shared.modules.CooldownModule;
import dev._2lstudios.chatsentinel.shared.modules.FloodModule;
import dev._2lstudios.chatsentinel.shared.modules.GeneralModule;
import dev._2lstudios.chatsentinel.shared.modules.MessagesModule;
import dev._2lstudios.chatsentinel.shared.modules.WhitelistModule;
import dev._2lstudios.chatsentinel.shared.utils.VersionUtil;

public class AsyncPlayerChatListener implements Listener {
	private final ChatSentinel chatSentinel;
	private final ModuleManager moduleManager;
	private final ChatPlayerManager chatPlayerManager;

	public AsyncPlayerChatListener(final ChatSentinel chatSentinel, final ModuleManager moduleManager,
			final ChatPlayerManager chatPlayerManager) {
		this.chatSentinel = chatSentinel;
		this.moduleManager = moduleManager;
		this.chatPlayerManager = chatPlayerManager;
	}

	@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
	public void onAsyncPlayerChat(final AsyncPlayerChatEvent event) {
		final Player player = event.getPlayer();

		if (!player.hasPermission("chatsentinel.bypass")) {
			final UUID uuid = player.getUniqueId();
			final ChatPlayer chatPlayer = chatPlayerManager.getPlayer(uuid);
			final String originalMessage = event.getMessage();
			final GeneralModule generalModule = moduleManager.getGeneralModule();
			final MessagesModule messagesModule = moduleManager.getMessagesModule();
			final WhitelistModule whitelistModule = moduleManager.getWhitelistModule();
			final Server server = chatSentinel.getServer();
			final String playerName = player.getName();
			final String lang = VersionUtil.getLocale(player);
			String message = originalMessage;

			if (generalModule.isSanitizeEnabled()) {
				message = generalModule.sanitize(message);
			}

			if (generalModule.isSanitizeNames()) {
				message = generalModule.sanitizeNames(server, message);
			}

			if (whitelistModule.isEnabled()) {
				final Pattern whitelistPattern = whitelistModule.getPattern();

				message = whitelistPattern.matcher(message)
						.replaceAll("");
			}

			message = message.trim();

			for (final Module module : moduleManager.getModules()) {
				if (!player.hasPermission("chatsentinel.bypass." + module.getName())
						&& module.meetsCondition(chatPlayer, message)) {
					final Collection<Player> recipients = event.getRecipients();
					final int warns = chatPlayer.addWarn(module), maxWarns = module.getMaxWarns();
					final String[][] placeholders = {
							{ "%player%", "%message%", "%warns%", "%maxwarns%", "%cooldown%" }, { playerName, originalMessage,
									String.valueOf(warns), String.valueOf(module.getMaxWarns()), String.valueOf(0) } };

					if (module instanceof BlacklistModule) {
						final BlacklistModule blacklistModule = (BlacklistModule) module;

						if (blacklistModule.isFakeMessage()) {
							recipients.removeIf(player1 -> player1 != player);
						} else if (blacklistModule.isHideWords()) {
							event.setMessage(blacklistModule.getPattern().matcher(message).replaceAll("***"));
						} else {
							event.setCancelled(true);
						}
					} else if (module instanceof CapsModule) {
						final CapsModule capsModule = (CapsModule) module;

						if (capsModule.isReplace()) {
							event.setMessage(originalMessage.toLowerCase());
						} else {
							event.setCancelled(true);
						}
					} else if (module instanceof CooldownModule) {
						placeholders[1][4] = String
								.valueOf(((CooldownModule) module).getRemainingTime(chatPlayer, message));

						event.setCancelled(true);
					} else if (module instanceof FloodModule) {
						final FloodModule floodModule = (FloodModule) module;

						if (floodModule.isReplace()) {
							final String replacedString = floodModule.replace(originalMessage);

							if (!replacedString.isEmpty()) {
								event.setMessage(replacedString);
							} else {
								event.setCancelled(true);
							}
						} else {
							event.setCancelled(true);
						}
					} else {
						event.setCancelled(true);
					}

					final String notificationMessage = module.getWarnNotification(placeholders);
					final String warnMessage = messagesModule.getWarnMessage(placeholders, lang, module.getName());

					if (warnMessage != null && !warnMessage.isEmpty())
						player.sendMessage(warnMessage);

					if (notificationMessage != null && !notificationMessage.isEmpty()) {
						for (final Player player1 : server.getOnlinePlayers()) {
							if (player1.hasPermission("chatsentinel.notify"))
								player1.sendMessage(notificationMessage);
						}

						server.getConsoleSender().sendMessage(notificationMessage);
					}

					if (warns >= maxWarns && maxWarns > 0) {
						server.getScheduler().runTask(chatSentinel, () -> {
							for (final String command : module.getCommands(placeholders)) {
								server.dispatchCommand(server.getConsoleSender(), command);
							}
						});

						chatPlayer.clearWarns();

						if (event.isCancelled()) {
							break;
						}
					}
				}
			}

			if (!event.isCancelled()) {
				final CooldownModule cooldownModule = moduleManager.getCooldownModule();
				final long currentMillis = System.currentTimeMillis();

				chatPlayer.addLastMessage(message, currentMillis);
				cooldownModule.setLastMessage(message, currentMillis);
			}
		}
	}
}
