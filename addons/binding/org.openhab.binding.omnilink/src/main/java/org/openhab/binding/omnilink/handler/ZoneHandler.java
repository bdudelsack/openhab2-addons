package org.openhab.binding.omnilink.handler;

import java.math.BigDecimal;

import org.eclipse.smarthome.core.library.types.DecimalType;
import org.eclipse.smarthome.core.library.types.OpenClosedType;
import org.eclipse.smarthome.core.library.types.StringType;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.UID;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.State;
import org.eclipse.smarthome.core.types.UnDefType;
import org.openhab.binding.omnilink.OmnilinkBindingConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.digitaldan.jomnilinkII.Message;
import com.digitaldan.jomnilinkII.OmniInvalidResponseException;
import com.digitaldan.jomnilinkII.OmniUnknownMessageTypeException;
import com.digitaldan.jomnilinkII.MessageTypes.ObjectStatus;
import com.digitaldan.jomnilinkII.MessageTypes.SecurityCodeValidation;
import com.digitaldan.jomnilinkII.MessageTypes.statuses.ZoneStatus;

public class ZoneHandler extends AbstractOmnilinkHandler {
    private Logger logger = LoggerFactory.getLogger(ZoneHandler.class);

    public ZoneHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        logger.debug("handleCommand: {}, command: {}", channelUID, command);
        if (command instanceof StringType) {
            int mode;
            switch (channelUID.getId()) {
                case "bypass":
                    mode = OmniLinkCmd.CMD_SECURITY_BYPASS_ZONE.getNumber();
                    break;
                case "restore":
                    mode = OmniLinkCmd.CMD_SECURITY_RESTORE_ZONE.getNumber();
                    break;
                default:
                    mode = -1;
            }
            int zoneNumber = ((BigDecimal) getThing().getConfiguration()
                    .get(OmnilinkBindingConstants.THING_PROPERTIES_NUMBER)).intValue();
            int areaNumber = Integer
                    .parseInt(getThing().getProperties().get(OmnilinkBindingConstants.THING_PROPERTIES_AREA));
            logger.debug("mode {} on zone {} with code {}", mode, zoneNumber, command.toFullString());
            char[] code = command.toFullString().toCharArray();
            if (code.length != 4) {
                logger.error("Invalid code length, code must be 4 digits");
            } else {
                try {
                    SecurityCodeValidation codeValidation = getOmnilinkBridgeHander().reqSecurityCodeValidation(
                            areaNumber, Character.getNumericValue(code[0]), Character.getNumericValue(code[1]),
                            Character.getNumericValue(code[2]), Character.getNumericValue(code[3]));
                    /*
                     * 0 Invalid code
                     * 1 Master
                     * 2 Manager
                     * 3 User
                     */
                    logger.debug("User code number:{} level:{}", codeValidation.getCodeNumber(),
                            codeValidation.getAuthorityLevel());
                    /*
                     * Valid user code number are 1-99, 251 is duress code, 0 means code does not exist
                     */
                    if ((codeValidation.getCodeNumber() > 0 && codeValidation.getCodeNumber() <= 99)
                            && codeValidation.getAuthorityLevel() > 0) {
                        getOmnilinkBridgeHander().sendOmnilinkCommand(mode, codeValidation.getCodeNumber(), zoneNumber);
                    } else {
                        logger.error("System reported an invalid code");
                    }
                } catch (OmniInvalidResponseException e) {
                    logger.debug("Zone command failed", e);
                } catch (OmniUnknownMessageTypeException | BridgeOfflineException e) {
                    logger.error("Could not send area command", e);
                }
            }
            // this is a send only channel, so don't store the user code
            updateState(channelUID, UnDefType.UNDEF);
        }
    }

    public void handleZoneStatus(ZoneStatus status) {

        int current = ((status.getStatus() >> 0) & 0x03);
        int latched = ((status.getStatus() >> 2) & 0x03);
        int arming = ((status.getStatus() >> 4) & 0x03);
        State contactState = current == 0 ? OpenClosedType.CLOSED : OpenClosedType.OPEN;
        logger.debug("handle Zone Status Change to state:{} current:{} latched:{} arming:{}", contactState, current,
                latched, arming);
        updateState(OmnilinkBindingConstants.CHANNEL_ZONE_CONTACT, contactState);
        updateState(OmnilinkBindingConstants.CHANNEL_ZONE_CURRENT_CONDITION, new DecimalType(current));
        updateState(OmnilinkBindingConstants.CHANNEL_ZONE_LATCHED_ALARM_STATUS, new DecimalType(latched));
        updateState(OmnilinkBindingConstants.CHANNEL_ZONE_ARMING_STATUS, new DecimalType(arming));

    }

    @Override
    public void channelLinked(ChannelUID channelUID) {
        logger.debug("channel linked: {}", channelUID);
        String[] channelParts = channelUID.getAsString().split(UID.SEPARATOR);
        int zoneId = Integer.parseInt(channelParts[channelParts.length - 2]);
        ObjectStatus objStatus;
        try {
            objStatus = getOmnilinkBridgeHander().requestObjectStatus(Message.OBJ_TYPE_ZONE, zoneId, zoneId, false);
            handleZoneStatus((ZoneStatus) objStatus.getStatuses()[0]);
        } catch (OmniInvalidResponseException | OmniUnknownMessageTypeException | BridgeOfflineException e) {
            logger.debug("Unexpected exception refreshing zone:", e);
        }
    }
}