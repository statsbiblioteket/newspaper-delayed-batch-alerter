package dk.statsbiblioteket.newspaper.delayalerter;

import dk.statsbiblioteket.medieplatform.autonomous.AbstractRunnableComponent;
import dk.statsbiblioteket.medieplatform.autonomous.AutonomousComponentUtils;
import dk.statsbiblioteket.medieplatform.autonomous.Batch;
import dk.statsbiblioteket.medieplatform.autonomous.CallResult;
import dk.statsbiblioteket.medieplatform.autonomous.ConfigConstants;
import dk.statsbiblioteket.medieplatform.autonomous.Event;
import dk.statsbiblioteket.medieplatform.autonomous.ResultCollector;
import dk.statsbiblioteket.medieplatform.autonomous.RunnableComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.mail.MessagingException;
import java.io.IOException;
import java.util.Arrays;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Properties;

/**
 * This is an autonomous component which checks if a batch has been active for too long without reaching
 * a completed status. Specifically it looks for batches with a "Data_Received" event but without an
 * "Approved" event and for which the "Data_Received" is more than XX days old. If this is the case
 * then it sends a warning email to a list of addresses. As it is a runnable component, this will only
 * occur once per batch.
 */
public class DelayAlerterComponent extends AbstractRunnableComponent {

    private DelayAlertMailer mailer;

    public static String EMAIL_SENT_EVENT = "Warning_Email_Sent";

    private static Logger log = LoggerFactory.getLogger(DelayAlerterComponent.class);

    /**
     * This is the main method for this autonomous component. It's arguments should be
     * -c \<config.properties>
     *
     * @param args the arguments.
     * @throws IOException
     */
    public static void main(String[] args) throws IOException {
        System.exit(doMain(args));
    }

    static int doMain(String[] args) throws IOException {
        log.info("Starting with args {}", new Object[]{args});
        Properties properties = AutonomousComponentUtils.parseArgs(args);
        DelayAlertMailer mailer = new DelayAlertMailer(
                properties.getProperty(DelayAlerterConfigConstants.EMAIL_FROM_ADDRESS),
                properties.getProperty(DelayAlerterConfigConstants.SMTP_HOST),
                properties.getProperty(DelayAlerterConfigConstants.SMTP_PORT));
        RunnableComponent component = new DelayAlerterComponent(properties, mailer);
        CallResult result = AutonomousComponentUtils.startAutonomousComponent(properties, component);
        System.out.println(result);
        return result.containsFailures();
    }

    public DelayAlerterComponent(Properties properties, DelayAlertMailer mailer ) {
        super(properties);
        this.mailer = mailer;
    }

    @Override
    public String getEventID() {
        return EMAIL_SENT_EVENT;
    }

    @Override
    public void doWorkOnBatch(Batch batch, ResultCollector resultCollector) throws Exception {
        List<Event> events=  batch.getEventList();
        for (Event event: events) {
            if (event.getEventID().equals("Data_Received")) {
                processDataReceivedEvent(batch, resultCollector, event);
            }
        }
    }

    /**
     * This method checks if the processing has taken too long. If it has, the event is passed on to sendAlertMail() for
     * further processing. Otherwise the resultCollector is set to non-preservable and the method just returns,
     * @param batch
     * @param resultCollector
     * @param event
     * @throws MessagingException
     */
    private void processDataReceivedEvent(Batch batch, ResultCollector resultCollector, Event event) throws MessagingException {
        Date now = new Date();
        Date receivedDate = event.getDate();
        Long alertPeriod = Integer.parseInt(
                getProperties().getProperty(DelayAlerterConfigConstants.DELAY_ALERT_DAYS))*24*3600*1000L;
        if (now.getTime() - receivedDate.getTime() > alertPeriod) {
            resultCollector.setPreservable(true);
            sendAlertMail(batch, resultCollector, event);
        } else {
            resultCollector.setPreservable(false);
            return;
        }
    }

    /**
     * Send the email alert.
     * @param batch
     * @param resultCollector
     * @param event
     * @throws MessagingException
     */
    private void sendAlertMail(Batch batch, ResultCollector resultCollector, Event event) throws MessagingException {
        String subject = "[Newspaper Delay Alert]" + batch.getFullID();
        String text = "Batch roundtrip " + batch.getFullID() + " was received at " + event.getDate() +
                "\n but has not yet been approved or rejected.";
        String[] mailRecipients = getProperties().getProperty(DelayAlerterConfigConstants.DELAY_ALERT_EMAIL_ADDRESSES).split(",");
        mailer.sendMail(Arrays.asList(mailRecipients), subject, text);
    }
}
