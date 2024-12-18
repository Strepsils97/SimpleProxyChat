package com.beanbeanjuice.simpleproxychat.discord;

import com.beanbeanjuice.simpleproxychat.utility.helper.Helper;
import com.beanbeanjuice.simpleproxychat.utility.config.Config;
import com.beanbeanjuice.simpleproxychat.utility.config.ConfigKey;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.ChunkingFilter;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;

import java.awt.*;
import java.time.Duration;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class Bot {

    private final Config config;
    private final Consumer<String> errorLogger;
    private JDA bot;

    private final Supplier<Integer> getOnlinePlayers;
    private final Supplier<Integer> getMaxPlayers;

    private final Queue<Runnable> runnableQueue;

    private boolean channelTopicErrorSent = false;

    public Bot(final Config config, final Consumer<String> errorLogger, final Supplier<Integer> getOnlinePlayers, final Supplier<Integer> getMaxPlayers) {
        this.config = config;
        this.errorLogger = errorLogger;

        this.getOnlinePlayers = getOnlinePlayers;
        this.getMaxPlayers = getMaxPlayers;

        this.runnableQueue = new ConcurrentLinkedQueue<>();

        if (!config.get(ConfigKey.USE_DISCORD).asBoolean()) {
            bot = null;
            return;
        }

        config.addReloadListener(this::updateActivity);
        config.addReloadListener(this::updateStatus);
    }

    private void sendMessageToChannel(TextChannel channel, String messageToSend) {
        String message = Helper.sanitize(messageToSend);
        message = Arrays.stream(message.split(" ")).map((originalString) -> {
            if (!originalString.startsWith("@")) return originalString;
            String name = originalString.replace("@", "");

            List<Member> potentialMembers = channel.getMembers();
            Optional<Member> potentialMember = potentialMembers
                    .stream()
                    .filter((member) -> ((member.getNickname() != null && member.getNickname().equalsIgnoreCase(name)) || member.getEffectiveName().equalsIgnoreCase(name)))
                    .findFirst();

            return potentialMember.map(IMentionable::getAsMention).orElse(originalString);
        }).collect(Collectors.joining(" "));

        channel.sendMessage(message).queue();
    }

    public void sendPrivateMessage(final String messageToSend) {
        if (bot == null) return;
        this.getBotPrivateTextChannel().ifPresentOrElse(
                (channel) -> sendMessageToChannel(channel, messageToSend),
                () -> errorLogger.accept("There was an error sending a message to Discord. Does the channel exist? Does the bot have access to the channel?")
        );
    }

    public void sendMessage(final String messageToSend) {
        if (bot == null) return;
        this.getBotTextChannel().ifPresentOrElse(
            (channel) -> sendMessageToChannel(channel, messageToSend),
            () -> errorLogger.accept("There was an error sending a message to Discord. Does the channel exist? Does the bot have access to the channel?")
        );
    }

    /**
     * Embed needs to be sanitized before running this function.
     * @param embed The {@link MessageEmbed} to send in the channel.
     */
    public void sendMessageEmbed(final MessageEmbed embed) {
        if (bot == null) return;

        this.getBotTextChannel().ifPresentOrElse(
                (channel) -> channel.sendMessageEmbeds(sanitizeEmbed(embed)).queue(),
                () -> errorLogger.accept("There was an error sending a message to Discord. Does the channel exist? Does the bot have access to the channel?")
        );
    }

    public Optional<TextChannel> getBotTextChannel() {
        return Optional.ofNullable(bot.getTextChannelById(config.get(ConfigKey.CHANNEL_ID).asString()));
    }

    public Optional<TextChannel> getBotPrivateTextChannel() {
        return Optional.ofNullable(bot.getTextChannelById(config.get(ConfigKey.PRIVATE_CHANNEL_ID).asString()));
    }

    private MessageEmbed sanitizeEmbed(final MessageEmbed oldEmbed) {
        EmbedBuilder embedBuilder = new EmbedBuilder(oldEmbed);

        if (oldEmbed.getTitle() != null)
            embedBuilder.setTitle(Helper.sanitize(oldEmbed.getTitle()));

        if (oldEmbed.getAuthor() != null)
            embedBuilder.setAuthor(
                    Helper.sanitize(oldEmbed.getAuthor().getName()),
                    oldEmbed.getAuthor().getUrl(),
                    oldEmbed.getAuthor().getIconUrl()
            );

        if (oldEmbed.getDescription() != null)
            embedBuilder.setDescription(Helper.sanitize(oldEmbed.getDescription()));

        if (oldEmbed.getFooter() != null)
            embedBuilder.setFooter(
                    Helper.sanitize(oldEmbed.getFooter().getText()),
                    oldEmbed.getFooter().getIconUrl()
            );

        if (!oldEmbed.getFields().isEmpty()) {
            List<MessageEmbed.Field> fields = new ArrayList<>(oldEmbed.getFields());  // Make copy.
            embedBuilder.clearFields();  // Clear fields.

            for (MessageEmbed.Field field : fields) {
                embedBuilder.addField(
                        Helper.sanitize(field.getName()),
                        Helper.sanitize(field.getValue()),
                        field.isInline()
                );
            }
        }

        return embedBuilder.build();
    }

    public void updateChannelTopic(final String topic) {
        if (bot == null) return;

        this.getBotTextChannel().ifPresentOrElse(
                (textChannel) -> {
                    try {
                        textChannel.getManager().setTopic(topic).queue();
                    } catch (InsufficientPermissionException e) {
                        if (!channelTopicErrorSent) {
                            channelTopicErrorSent = true;
                            errorLogger.accept("""
                                    No permission to edit channel topic. If you don't want the channel topics to be updated, \
                                    simply ignore this message. Otherwise, please give the Discord bot the MANAGE_CHANNELS \
                                    permission. This message will only be sent once per server restart. \
                                    """);
                        }
                    }
                },
                () -> errorLogger.accept("There was an error updating the Discord channel topic. Does the channel exist? Does the bot have access to the channel?")
        );
    }

    public void channelUpdaterFunction() {
        if (bot == null) return;
        String topicMessage = config.get(ConfigKey.DISCORD_TOPIC_ONLINE).asString().replace("%online%", String.valueOf(getOnlinePlayers.get()));
        this.updateChannelTopic(topicMessage);
    }

    public Optional<JDA> getJDA() {
        return Optional.ofNullable(bot);
    }

    public void addRunnableToQueue(final Runnable runnable) {
        this.runnableQueue.add(runnable);
    }

    public void start() throws InterruptedException {
        String token = config.get(ConfigKey.BOT_TOKEN).asString();
        if (token.isEmpty() || token.equalsIgnoreCase("TOKEN_HERE") || token.equalsIgnoreCase("null")) return;

        bot = JDABuilder
                .createLight(token)
                .setActivity(Activity.watching("Starting Proxy..."))
                .enableCache(CacheFlag.ROLE_TAGS)
                .setMemberCachePolicy(MemberCachePolicy.ALL)
                .setChunkingFilter(ChunkingFilter.ALL)
                .enableIntents(GatewayIntent.MESSAGE_CONTENT, GatewayIntent.GUILD_MEMBERS)
                .build().awaitReady();

        sendProxyStatus(true);

        this.updateActivity();
        this.updateStatus();

        this.runnableQueue.forEach(Runnable::run);
    }

    public void updateActivity() {
        this.getJDA().ifPresent((jda) -> {
            int onlinePlayers = getOnlinePlayers.get();
            int maxPlayers = getMaxPlayers.get();

            Activity.ActivityType type;
            String text;

            try {
                type = Activity.ActivityType.valueOf(config.get(ConfigKey.BOT_ACTIVITY_TYPE).asString());
                text = config.get(ConfigKey.BOT_ACTIVITY_TEXT).asString();
            } catch (Exception e) {
                type = Activity.ActivityType.WATCHING;
                text = "CONFIG ERROR";
            }

            text = text.replace("%online%", String.valueOf(onlinePlayers))
                       .replace("%max-players%", String.valueOf(maxPlayers));
            jda.getPresence().setActivity(Activity.of(type, text));
        });
    }

    public void updateStatus() {
        this.getJDA().ifPresent((jda) -> {
            OnlineStatus status;

            try {
                status = OnlineStatus.valueOf(config.get(ConfigKey.BOT_ACTIVITY_STATUS).asString());
            } catch (Exception e) {
                status = OnlineStatus.IDLE;
            }
            jda.getPresence().setStatus(status);
        });
    }

    public void sendProxyStatus(final boolean isStart) {
        if (!config.get(ConfigKey.DISCORD_PROXY_STATUS_ENABLED).asBoolean()) return;

        if (isStart) {
            this.sendMessageEmbed(
                    new EmbedBuilder()
                            .setTitle(config.get(ConfigKey.DISCORD_PROXY_STATUS_MODULE_ENABLED).asString())
                            .setColor(Color.GREEN)
                            .build()
            );
        } else {
            this.sendMessageEmbed(
                    new EmbedBuilder()
                            .setTitle(config.get(ConfigKey.DISCORD_PROXY_STATUS_MODULE_DISABLED).asString())
                            .setColor(Color.RED)
                            .build()
            );
        }
    }

    public void stop() {
        if (bot == null) return;
        sendProxyStatus(false);

        this.updateChannelTopic(config.get(ConfigKey.DISCORD_TOPIC_OFFLINE).asString());

        this.getJDA().ifPresent((jda) -> {
            try {
                jda.shutdown();
                if (!jda.awaitShutdown(Duration.ofSeconds(10))) {
                    jda.shutdownNow(); // Cancel all remaining requests
                    jda.awaitShutdown(); // Wait until shutdown is complete (indefinitely)
                }
            } catch (InterruptedException ignored) { }
        });
    }

}
