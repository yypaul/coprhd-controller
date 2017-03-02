/*
 * Copyright (c) 2015 EMC Corporation
 * All Rights Reserved
 */
package controllers.infra;

import static com.emc.storageos.model.property.PropertyConstants.ENCRYPTEDTEXT;
import static com.emc.storageos.model.property.PropertyConstants.TEXT;
import static controllers.Common.flashException;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import com.emc.storageos.management.backup.ExternalServerType;
import com.emc.storageos.management.backup.util.BackupClient;
import com.emc.storageos.management.backup.util.CifsClient;
import com.emc.storageos.management.backup.util.FtpClient;
import models.properties.BackupPropertyPage;
import models.properties.ControllerPropertyPage;
import models.properties.DefaultPropertyPage;
import models.properties.DiscoveryPropertyPage;
import models.properties.NetworkPropertyPage;
import models.properties.PasswordPropertyPage;
import models.properties.Property;
import models.properties.PropertyPage;
import models.properties.SecurityPropertyPage;
import models.properties.SmtpPropertyPage;
import models.properties.SupportPropertyPage;
import models.properties.SyslogPropertiesPage;
import models.properties.UpgradePropertyPage;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.math.NumberUtils;

import org.apache.http.auth.AuthenticationException;
import play.Logger;
import play.data.validation.Required;
import play.data.validation.Validation;
import play.i18n.Messages;
import play.mvc.Controller;
import play.mvc.With;
import util.BourneUtil;
import util.ConfigPropertyUtils;
import util.DisasterRecoveryUtils;
import util.MailSettingsValidator;
import util.MessagesUtils;
import util.PasswordUtil;
import util.SetupUtils;
import util.ValidationResponse;
import util.validation.HostNameOrIpAddressCheck;

import com.emc.storageos.model.property.PropertyMetadata;
import com.emc.storageos.security.password.Constants;
import com.emc.storageos.services.util.PlatformUtils;
import com.emc.vipr.model.sys.ClusterInfo;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import controllers.Common;
import controllers.Maintenance;
import controllers.deadbolt.Restrict;
import controllers.deadbolt.Restrictions;
import controllers.security.Security;

@With(Common.class)
@Restrictions({ @Restrict("SECURITY_ADMIN"), @Restrict("RESTRICTED_SECURITY_ADMIN") })
public class ConfigProperties extends Controller {

    private static final String DEFAULT_PAGE = "general";
    private static final String OTHER = "Other";
    private static final String SECURITY_PAGE = "Security";
    private static final String PERMIT_ROOT_CONSOLE = "system_permit_root_console";
    private static final String PERMIT_ROOT_SSH = "system_permit_root_ssh";
    private static final int MAX_FLASH = 2048;

    public static void properties() {
        ClusterInfo clusterInfo = Common.getClusterInfoWithRoleCheck();
        List<PropertyPage> pages = loadPropertyPages();
        render(pages, clusterInfo);
    }

    private static void handleError(Map<String, String> updated) {
        // Limit the amount of data flashed to 2K, hopefully leaving enough for other things
        int maxFlash = MAX_FLASH;

        if (updated.size() != 0) {
            int avgFlash = maxFlash / updated.size();

            // Only flash properties that have been updated
            for (Map.Entry<String, String> entry : updated.entrySet()) {
                String name = entry.getKey();
                String value = entry.getValue();
                // Avoid blowing up the cookie by flashing too much
                if (StringUtils.length(value) < avgFlash) {
                    params.flash(name);
                }
                else {
                    Logger.info("Could not flash property: %s, value is too long: %s", name, value.length());
                }
            }
        }
        Validation.keep();
        properties();
    }

    public static void saveProperties() {
        Map<String, String> properties = params.allSimple();
        for (Entry<String, String> entry : properties.entrySet()) {
            entry.setValue(StringUtils.trim(entry.getValue()));
        }

        List<PropertyPage> pages = loadPropertyPages();
        for (PropertyPage page : pages) {
            page.validate(properties);
        }
        boolean rebootRequired = false;
        Map<String, String> updated = Maps.newHashMap();
        for (PropertyPage page : pages) {
            Map<String, String> pageUpdates = page.getUpdatedValues(properties);
            updated.putAll(pageUpdates);

            // Update the reboot required flag based on the values set for this page
            rebootRequired |= page.isRebootRequired(pageUpdates.keySet());
        }
        if (validation.hasErrors()) {
            flash.error(MessagesUtils.get("configProperties.error.validationError"));
            handleError(updated);
        }
        else if (!Common.isClusterStable()) {
            flash.error(MessagesUtils.get("configProperties.error.clusterNotStable"));
            handleError(updated);
        }
        else {
            if (!updated.isEmpty()) {
                // If reboot is required, submit as a job and go to maintenance page. The cluster reboots immediately
                if (rebootRequired) {
                    try {
                        ConfigPropertyUtils.saveProperties(updated);
                        flash.success(MessagesUtils.get("configProperties.submittedReboot"));
                        Maintenance.maintenance(Common.reverseRoute(ConfigProperties.class, "properties"));
                    } catch (Exception e) {
                        Logger.error("reboot exception - ", e);
                        flashException(e);
                        handleError(updated);
                    }
                }

                try {
                    ConfigPropertyUtils.saveProperties(updated);
                    flash.success(MessagesUtils.get("configProperties.saved"));
                } catch (Exception e) {
                    flashException(e);
                    handleError(updated);
                }
            }
            properties();
        }
    }

    private static List<PropertyPage> loadPropertyPages() {
        boolean isActiveSite = DisasterRecoveryUtils.isActiveSite();
        Map<String, Property> properties = loadProperties();
        Map<String, PropertyPage> pages = Maps.newLinkedHashMap();
        Map<String, PropertyPage> excludePages = Maps.newLinkedHashMap();

        if (isActiveSite) {
            addPage(pages, new NetworkPropertyPage(properties));
            if (PlatformUtils.isAppliance()) { // This done to maintain the current tab order
                addPage(pages, new SecurityPropertyPage(properties));
            }
            addPage(pages, new ControllerPropertyPage(properties));
            addPage(pages, new DiscoveryPropertyPage(properties));
            if (!SetupUtils.isOssBuild()) {
                addPage(pages, new SupportPropertyPage(properties));
            }
            addPage(pages, new SmtpPropertyPage(properties));
            addPage(pages, new UpgradePropertyPage(properties));
            addPage(pages, new PasswordPropertyPage(properties));
            addPage(pages, new SyslogPropertiesPage(properties));
            addPage(pages, new BackupPropertyPage(properties));
        }
        else {
            if (PlatformUtils.isAppliance()) {
                addPage(pages, new SecurityPropertyPage(properties));
            }
            addPage(excludePages, new NetworkPropertyPage(properties));
            addPage(excludePages, new ControllerPropertyPage(properties));
            addPage(excludePages, new DiscoveryPropertyPage(properties));
            addPage(excludePages, new SupportPropertyPage(properties));
            addPage(excludePages, new SmtpPropertyPage(properties));
            addPage(excludePages, new UpgradePropertyPage(properties));
            addPage(excludePages, new DefaultPropertyPage(OTHER));
            addPage(excludePages, new PasswordPropertyPage(properties));
            addPage(excludePages, new SyslogPropertiesPage(properties));
            addPage(excludePages, new BackupPropertyPage(properties));
        }

        addDefaultPages(pages, properties.values(), excludePages, isActiveSite);

        return Lists.newArrayList(pages.values());
    }

    private static PropertyPage addPage(Map<String, PropertyPage> pages, PropertyPage page) {
        pages.put(page.getName(), page);
        return page;
    }

    private static void addDefaultPages(Map<String, PropertyPage> pages, Collection<Property> properties,
            Map<String, PropertyPage> excludePages, boolean isActiveSite) {
        for (Property property : properties) {
            String pageName = StringUtils.defaultIfBlank(property.getPageName(), DEFAULT_PAGE);
            PropertyPage page = pages.get(pageName);
            if (excludePages.get(pageName) == null) {
                if (page == null) {
                    page = addPage(pages, new DefaultPropertyPage(pageName));
                }
                if (isActiveSite) {
                    page.getProperties().add(property);
                }
                else {
                    String propertyName = StringUtils.defaultIfBlank(property.getName(), DEFAULT_PAGE);
                    if (StringUtils.equals(propertyName, PERMIT_ROOT_CONSOLE) || StringUtils.equals(propertyName, PERMIT_ROOT_SSH)) {
                        if (!StringUtils.equals(pageName, SECURITY_PAGE)) {
                            page.getProperties().add(property);
                        }
                    }
                    else {
                        page.getProperties().add(property);
                    }
                }
            }
        }
    }

    private static Map<String, Property> loadProperties() {
        Map<String, String> values = ConfigPropertyUtils.getProperties();
        Map<String, Property> properties = Maps.newLinkedHashMap();
        for (Map.Entry<String, PropertyMetadata> entry : ConfigPropertyUtils.getPropertiesMetadata().getMetadata()
                .entrySet()) {
            /*
             * image server configuration has been moved to Physical assets but, image server properties meta data
             * needs to remain for migration
             */
            if (entry.getKey().startsWith("image_server")) {
                continue;
            }
            PropertyMetadata metadata = entry.getValue();
            if ((metadata.getUserMutable() != null && metadata.getUserMutable())
                    && (metadata.getHidden() == null || !metadata.getHidden())) {
                String name = entry.getKey();
                String value = values.get(name);
                PropertyMetadata meta = entry.getValue();
                if (value != null) {
                    if (meta.getType().equals(TEXT) || meta.getType().equals(ENCRYPTEDTEXT)) {
                        value = value.replace("\\\\n", "\r\n");
                    }
                }
                Set<String> allSupportPageProperties = SupportPropertyPage.getAllProperties();
                if (!(allSupportPageProperties.contains(name) && SetupUtils.isOssBuild())) {
                    Property property = new Property(name, value, meta);
                    properties.put(name, property);
                }
            }
        }
        return properties;
    }

    public static String getPort(final String port, final String enableTls) {
        if ("0".equals(port) || StringUtils.isBlank(port)) {
            // use default values
            return StringUtils.equals("yes", enableTls) ? "465" : "25";
        }
        else {
            return port;
        }
    }
    public static void validateExternalSettings(String serverType, String serverUrl, String serverDomain, String user,
                                                String password) {
        BackupClient client;
        String passwd = PasswordUtil.decryptedValue(password);
        if (serverType.equalsIgnoreCase(ExternalServerType.CIFS.name())) {
            if (!serverUrl.startsWith("smb://")) {
                Validation.addError(null,Messages.get("configProperties.backup.serverType.invalid"));
                renderJSON(ValidationResponse.collectErrors());
            }
            client = new CifsClient(serverUrl, serverDomain, user, passwd);
        } else {
            if (!(serverUrl.startsWith("ftp://")|| serverUrl.startsWith("ftps://"))) {
                Validation.addError(null,Messages.get("configProperties.backup.serverType.invalid"));
                renderJSON(ValidationResponse.collectErrors());
            }
            client = new FtpClient(serverUrl, user, passwd);
        }
        try {
            client.validate();
        }catch (AuthenticationException e ){
            Validation.addError(null,Messages.get("configProperties.backup.credential.invalid"),e.getMessage());
        }catch (ConnectException e) {
            Validation.addError(null,Messages.get("configProperties.backup.server.invalid"), e.getMessage());
        }

        if (Validation.hasErrors()) {
            renderJSON(ValidationResponse.collectErrors());
        } else {
            renderJSON(ValidationResponse.valid(Messages.get("configProperties.backup.testSuccessful")));
        }

    }

  public static void connectExternalServerIpPort(String ipPortListStr){
        if (ipPortListStr.isEmpty()) {
            renderJSON(ValidationResponse.invalid(Messages.get("configProperties.syslog.serverPort.unavailable",null)));
        }
        String[] ipPortList = ipPortListStr.split(",");
        for (String ipPort : ipPortList) {
            String[] ipPortSpliter = ipPort.split(":");
            String ip = ipPortSpliter[0];
            String port = ipPortSpliter[1];
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(ip,Integer.parseInt(port)),2*1000);
                socket.close();
            }catch (Exception e ){
                Validation.addError(null,Messages.get("configProperties.syslog.serverPort.unavailable",ipPort));
            }
        }
        if (Validation.hasErrors()) {
            renderJSON(ValidationResponse.collectErrors());
        } else {
            renderJSON(ValidationResponse.valid(Messages.get("configProperties.syslog.serverPort.successful")));
        }
    }

    public static void validateMailSettings(String server, String port, String username, String password, String enableTls,
            String authType, String fromAddress, String toAddress) {
        password = PasswordUtil.decryptedValue(password);

        if (StringUtils.isBlank(server)) {
            Validation.addError(null, "configProperties.smtp.server.required");
        }
        else if (!HostNameOrIpAddressCheck.isValidHostNameOrIp(server)) {
            Validation.addError(null, "configProperties.smtp.server.invalid", server);
        }

        if (StringUtils.isNotBlank(port)) {
            int value = NumberUtils.toInt(port, 0);
            if (value < 0) {
                Validation.addError(null, "configProperties.smtp.port.invalid", port);
            }
        }

        if (StringUtils.isBlank(fromAddress)) {
            Validation.addError(null, "configProperties.smtp.fromAddress.required");
        }
        else if (!Property.VALIDATOR.validateEmail(fromAddress)) {
            Validation.addError(null, "configProperties.smtp.fromAddress.invalid");
        }

        if (StringUtils.isBlank(toAddress)) {
            Validation.addError(null, "configProperties.smtp.toAddress.required");
        }
        else if (!Property.VALIDATOR.validateEmail(toAddress)) {
            Validation.addError(null, "configProperties.smtp.toAddress.invalid");
        }

        if (!StringUtils.equalsIgnoreCase("none", authType)) {
            if (StringUtils.isBlank(username)) {
                Validation.addError(null, "configProperties.smtp.username.required");
            }
            if (StringUtils.isBlank(password)) {
                Validation.addError(null, "configProperties.smtp.password.required");
            }
        }

        if (Validation.hasErrors()) {
            renderJSON(ValidationResponse.collectErrors());
        }

        MailSettingsValidator.Settings settings = new MailSettingsValidator.Settings();
        settings.server = server;
        settings.port = getPort(port, enableTls);
        settings.username = username;
        settings.password = password;
        settings.channel = StringUtils.equals("yes", enableTls) ? "starttls" : "clear";
        settings.authType = authType;
        settings.fromAddress = fromAddress;

        try {
            MailSettingsValidator.validate(settings, toAddress);
        } catch (RuntimeException e) {
            Logger.error(e, "Failed to send email");
            Validation.addError(null, "configProperties.smtp.validationFailed", e.getMessage());
        }

        if (Validation.hasErrors()) {
            renderJSON(ValidationResponse.collectErrors());
        }
        else {
            renderJSON(ValidationResponse.valid(Messages.get("configProperties.smtp.testSuccessful")));
        }
    }

    public static String getPasswordValidPromptRule() {
        String promptString = PasswordUtil.getPasswordValidPromptRules(Constants.PASSWORD_VALID_PROMPT);
        return promptString;
    }

    public static void validatePasswords(@Required String password, @Required String fieldName) {
        String validation = PasswordUtil.validatePassword(password);
        if (StringUtils.isNotBlank(validation)) {
            Validation.addError(fieldName, validation);
        }
        if (Validation.hasErrors()) {
            renderJSON(ValidationResponse.collectErrors());
        }
        else {
            renderJSON(ValidationResponse.valid());
        }

    }

    public static void passwords() {
        ClusterInfo clusterInfo = Common.getClusterInfoWithRoleCheck();
        render(clusterInfo);
    }

    public static void changePassword(@Required String user, @Required String password, @Required String passwordConfirm) {
        if (Validation.hasErrors()) {
            params.flash("user");
            validation.keep();
            passwords();
        }
        else if (!Common.isClusterStable()) {
            flash.error(MessagesUtils.get("configProperties.error.clusterNotStable"));
            params.flash("user");
            validation.keep();
            passwords();
        }
        else {
            if (!StringUtils.equals(password, passwordConfirm)) {
                flash.error(MessagesUtils.get("configProperties.password.doesNotMatch"));
            }
            else {
                try {
                    updateUserPassword(user, password);
                    flash.success(Messages.get("configProperties.passwordChange.success", user));

                    // We are changing our own password so clear our token forcing us to go to the login page
                    if (StringUtils.equals(Security.getUserInfo().getCommonName(), user)) {
                        Security.clearAuthToken();
                    }
                } catch (Exception e) {
                    Logger.error(e, "Failed to change password for user '%s'", user);
                    String message = Common.getUserMessage(e);
                    flash.error(Messages.get("configProperties.passwordChange.error", user, message));
                }
            }

            params.flash("user");
            passwords();
        }
    }

    private static void updateUserPassword(String user, String password) throws Exception {
        BourneUtil.getSysClient().password().reset(user, password, false);
    }
}
