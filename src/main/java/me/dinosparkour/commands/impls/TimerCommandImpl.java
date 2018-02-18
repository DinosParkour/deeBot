package me.dinosparkour.commands.impls;

import me.dinosparkour.managers.ServerManager;
import me.dinosparkour.managers.listeners.ShardManager;
import me.dinosparkour.utils.IOUtil;
import me.dinosparkour.utils.MessageUtil;
import net.dv8tion.jda.core.Permission;
import net.dv8tion.jda.core.entities.MessageChannel;
import net.dv8tion.jda.core.entities.User;
import net.dv8tion.jda.core.events.message.MessageReceivedEvent;

import java.io.File;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public abstract class TimerCommandImpl extends Command {

    private static final String DEFAULT_MESSAGE = "⏰  Time's up!";
    private final ScheduledExecutorService timerScheduler = Executors.newScheduledThreadPool(1);
    private final List<TimerImpl> announcementList = new ArrayList<>();
    private final List<TimerImpl> reminderList = new ArrayList<>();
    private final Map<String, ScheduledFuture> scheduledAnnouncements = new HashMap<>(); // Map<AuthorId, Runnable>
    private final Map<String, ScheduledFuture> scheduledReminders = new HashMap<>();     // Map<AuthorId, Runnable>
    private final String noTimers = "You have no " + getType().name().toLowerCase() + "s set!";

    public TimerCommandImpl() {
        if (IOUtil.createFile(getFile())) {
            IOUtil.readDataFileBlocking(getFile().getName(), () ->
                    IOUtil.readLinesFromFile(getFile()).forEach(s -> {
                        String[] constructors = s.split("\\|");
                        String time = constructors[0];
                        OffsetDateTime odt = OffsetDateTime.parse(constructors[0]);
                        String authorId = constructors[1];
                        String targetId = constructors[2];
                        String message = s.substring(time.length() + 1 + authorId.length() + 1 + targetId.length() + 1);
                        /*
                        boolean repeatable = isRepeatable(message);
                        if (repeatable) message = removeFlag(message);
                        */
                        TimerImpl impl = new TimerImpl(odt, authorId, targetId, message /*, repeatable */);
                        if (impl.getSecondsLeft() < 0) { // Delete reminder if it's been skipped
                            IOUtil.removeTextFromFile(getFile(), s);
                        } else { // Add a new entry if the reminder is valid
                            addEntry(impl);
                        }
                    })
            );
        }
    }

    protected abstract Type getType();

    @Override
    public void executeCommand(String[] args, MessageReceivedEvent e, MessageSender chat) {
        String typeName = getType().name().toLowerCase();
        String typeSet = getType().pronoun + typeName;

        switch (args.length) {
            case 0:
                if (hasSetTimer(e.getAuthor())) { // The user has a timer set
                    TimerImpl timer = getSetTimer(e.getAuthor());
                    chat.sendMessage("You have "
                            + /* (timer.isRepeatable() ? "a repeated " : */ getType().pronoun //)
                            + typeName + " set "
                            + "with the following message:\n"
                            + (timer.getMessage() == null ? DEFAULT_MESSAGE : timer.getMessage()));
                } else { // No timer has been set
                    chat.sendMessage(noTimers);
                }
                break;

            case 1:
                if (args[0].equalsIgnoreCase("reset")) {
                    if (hasSetTimer(e.getAuthor())) {
                        chat.sendMessage("Successfully deleted your " + typeName + "!");
                        removeEntry(getSetTimer(e.getAuthor()));
                    } else {
                        chat.sendMessage(noTimers);
                    }
                } else {
                    chat.sendUsageMessage();
                }
                break;

            default:
                if (!isReminder() && !e.getMember().hasPermission(e.getTextChannel(), Permission.MESSAGE_MANAGE)) {
                    chat.sendMessage("You need `[MESSAGE_MANAGE]` in order to create announcements for this channel!");
                } else if (!hasSetTimer(e.getAuthor())) {
                /*
                 * timer [duration] [time unit] (timer text)
                 *        args[0]    args[1]     args[...]
                 */
                    int duration;
                    try {
                        duration = Integer.parseInt(args[0]);
                        if (duration < 0) throw new NumberFormatException();
                    } catch (NumberFormatException ex) {
                        chat.sendMessage("That's not a valid duration amount!");
                        return;
                    }

                    Unit unit = Unit.get(args[1]);
                    ChronoUnit chronoUnit;
                    if (unit == null) {
                        chat.sendMessage("**That's not a valid time unit!** (seconds, minutes, hours, days)");
                        return;
                    } else {
                        chronoUnit = unit.chronoUnit;
                    }

                    OffsetDateTime odt = OffsetDateTime.now().plus(duration, chronoUnit);
                    String authorId = e.getAuthor().getId();
                    String message = e.getMessage().getContentRaw()
                            .substring(e.getMessage().getContentRaw().indexOf(" ") + 1 + args[0].length() + 1 + args[1].length())
                            .trim().replace("\n", "\\n");

                    /*
                    boolean repeatable = isRepeatable(message);
                    if (isRepeatable(message)) {
                        if (chronoUnit.equals(ChronoUnit.HOURS) || chronoUnit.equals(ChronoUnit.DAYS))
                            message = removeFlag(message);
                        else {
                            chat.sendMessage("To reduce load on Discord's servers, "
                                    + "please select either hours or days as your time unit for a repeated " + typeName + ".");
                            return;
                        }
                    }
                    */

                    if (isReminder()) {
                        e.getAuthor().openPrivateChannel().queue(c -> createImpl(odt, authorId, c.getId(), message));
                    } else {
                        createImpl(odt, authorId, e.getTextChannel().getId(), message);
                    }
                    chat.sendMessage("Your " + typeName + " has been set!", e.getChannel());
                } else {
                    chat.sendMessage("You already have " + typeSet + " set! Use " + getPrefix(e.getGuild()) + getName() + " to review it.");
                }
                break;
        }
    }

    @Override
    public String getName() {
        return getType().name().toLowerCase();
    }

    @Override
    public List<String> getOptionalParams() {
        return Arrays.asList("duration / reset", "time unit", "text");
    }

    /*
    @Override
    public Map<String, String> getFlags() {
        return Collections.singletonMap("--repeat", "Create a repeatable " + getType().name().toLowerCase());
    }
    */

    private List<TimerImpl> getList() {
        return isReminder() ? reminderList : announcementList;
    }

    private Map<String, ScheduledFuture> getMap() {
        return isReminder() ? scheduledReminders : scheduledAnnouncements;
    }

    private String getEntryLine(TimerImpl impl) {
        return impl.getTimeStamp() + "|"
                + impl.getAuthorId() + "|"
                + impl.getTargetId() + "|"
                + impl.getMessage()
                /* + (impl.isRepeatable() ? " --repeat" : "") */;
    }

    private void createImpl(OffsetDateTime odt, String authorId, String targetId, String message) {
        TimerImpl impl = new TimerImpl(odt, authorId, targetId, message);
        addEntry(impl);
        IOUtil.writeTextToFile(getFile(), getEntryLine(impl), true);
    }

    private void addEntry(TimerImpl impl) {
        ScheduledFuture future = /* impl.isRepeatable()
                ? timerScheduler.scheduleWithFixedDelay(timerRunnable(impl), impl.getSecondsLeft(), impl.getSecondsLeft(), TimeUnit.SECONDS)
                : */ timerScheduler.schedule(timerRunnable(impl), impl.getSecondsLeft(), TimeUnit.SECONDS);
        getList().add(impl);
        getMap().put(impl.getAuthorId(), future);
    }

    private void removeEntry(TimerImpl impl) {
        getList().remove(impl); // Remove the timer from the set
        IOUtil.removeTextFromFile(getFile(), getEntryLine(impl));
        getMap().get(impl.getAuthorId()).cancel(true); // Cancel the scheduled future
        getMap().remove(impl.getAuthorId());
    }

    private boolean userMatch(TimerImpl impl, User u) {
        return impl.getAuthorId().equals(u.getId());
    }

    private boolean hasSetTimer(User u) {
        return getList().stream().anyMatch(impl -> userMatch(impl, u));
    }

    private TimerImpl getSetTimer(User u) {
        return getList().stream().filter(impl -> userMatch(impl, u)).findFirst().orElse(null);
    }

    private File getFile() {
        return new File(ServerManager.getDataDir() + getType().name().toLowerCase() + "s.txt");
    }

    private Runnable timerRunnable(TimerImpl impl) {
        return () -> {
            String msg = impl.getMessage().isEmpty() ? DEFAULT_MESSAGE : impl.getMessage().replace("\\n", "\n");
            MessageChannel channel = isReminder()
                    ? ShardManager.getGlobalPrivateChannelById(impl.getTargetId())
                    : ShardManager.getGlobalTextChannelById(impl.getTargetId());
            if (channel != null) {
                MessageUtil.sendMessage(msg, channel, null, t -> { /* Do nothing */ });
            }

            /* if (!impl.isRepeatable()) */
            removeEntry(impl);
            //else impl.updateOdt();
        };
    }

    /*
    private boolean isRepeatable(String message) {
        return message.endsWith(" --repeat");
    }

    private String removeFlag(String message) {
        return message.substring(0, message.length() - "--repeat".length()).trim();
    }
    */

    private boolean isReminder() {
        return getType() == Type.REMINDER;
    }

    protected enum Type {
        ANNOUNCEMENT("an "), REMINDER("a ");

        private final String pronoun;

        Type(String pronoun) {
            this.pronoun = pronoun;
        }
    }

    private enum Unit {
        SECONDS(ChronoUnit.SECONDS, 1000, "s", "sec", "secs", "second", "seconds"),
        MINUTES(ChronoUnit.MINUTES, Unit.SECONDS.multiplier * 60, "m", "min", "mins", "minute", "minutes"),
        HOURS(ChronoUnit.HOURS, Unit.MINUTES.multiplier * 60, "h", "hour", "hours"),
        DAYS(ChronoUnit.DAYS, Unit.HOURS.multiplier * 24, "d", "day", "days");

        private final ChronoUnit chronoUnit;
        private final int multiplier;
        private final String[] measurements;

        Unit(ChronoUnit chronoUnit, int multiplier, String... measurements) {
            this.chronoUnit = chronoUnit;
            this.multiplier = multiplier;
            this.measurements = measurements;
        }

        static Unit get(String measurement) {
            for (Unit u : Unit.values()) {
                if (Arrays.asList(u.measurements).contains(measurement)) {
                    return u;
                }
            }
            return null;
        }
    }

    public class TimerImpl {
        private final String authorId;
        private final String targetId;
        private final String message;
        //private final boolean repeatable;
        //private final long duration;
        private final OffsetDateTime odt;

        TimerImpl(OffsetDateTime odt, String authorId, String targetId, String message /*, boolean repeatable */) {
            this.odt = odt;
            this.authorId = authorId;
            this.targetId = targetId;
            this.message = message;
            //this.repeatable = repeatable;
            //this.duration = getSecondsLeft();
        }

        OffsetDateTime getTimeStamp() {
            return odt;
        }

        long getSecondsLeft() {
            return Duration.between(OffsetDateTime.now(), odt).getSeconds();
        }

        /*
        String getTimeLeftFormatted() {
            return MessageUtil.formatTime(getSecondsLeft() * 1000);
        }
        */

        String getAuthorId() {
            return authorId;
        }

        String getTargetId() {
            return targetId;
        }

        String getMessage() {
            return message;
        }

        /*
        boolean isRepeatable() {
            return repeatable;
        }

        void updateOdt() {
            IOUtil.removeTextFromFile(getFile(), getEntryLine(this));
            this.odt = OffsetDateTime.now().plusSeconds(duration);
            IOUtil.writeTextToFile(getFile(), getEntryLine(this), true);
        }
        */
    }
}