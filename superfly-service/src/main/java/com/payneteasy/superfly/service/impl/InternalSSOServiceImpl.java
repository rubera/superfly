package com.payneteasy.superfly.service.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.payneteasy.superfly.api.*;
import com.payneteasy.superfly.dao.SessionDao;
import com.payneteasy.superfly.model.*;
import com.payneteasy.superfly.model.UserRegisterRequest;
import com.payneteasy.superfly.model.ui.action.UIAction;
import com.payneteasy.superfly.model.ui.action.UIActionForCheckboxForRole;
import com.payneteasy.superfly.model.ui.role.UIRole;
import com.payneteasy.superfly.model.ui.subsystem.UISubsystem;
import com.payneteasy.superfly.service.*;
import com.payneteasy.superfly.utils.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Required;
import org.springframework.transaction.annotation.Transactional;

import com.payneteasy.superfly.crypto.PublicKeyCrypto;
import com.payneteasy.superfly.dao.ActionDao;
import com.payneteasy.superfly.dao.UserDao;
import com.payneteasy.superfly.lockout.LockoutStrategy;
import com.payneteasy.superfly.model.ui.user.UserForDescription;
import com.payneteasy.superfly.password.PasswordEncoder;
import com.payneteasy.superfly.password.SaltSource;
import com.payneteasy.superfly.policy.impl.AbstractPolicyValidation;
import com.payneteasy.superfly.policy.password.PasswordCheckContext;
import com.payneteasy.superfly.register.RegisterUserStrategy;
import com.payneteasy.superfly.spi.HOTPProvider;
import com.payneteasy.superfly.spisupport.HOTPService;
import com.payneteasy.superfly.spisupport.SaltGenerator;
import com.payneteasy.superfly.utils.PGPKeyValidator;

@Transactional
public class InternalSSOServiceImpl implements InternalSSOService {

    private static final Logger logger = LoggerFactory.getLogger(InternalSSOServiceImpl.class);

    private UserDao              userDao;
    private ActionDao            actionDao;
    private SessionDao           sessionDao;
    private NotificationService  notificationService;
    private LoggerSink           loggerSink;
    private PasswordEncoder      passwordEncoder;
    private SaltSource           saltSource;
    private SaltGenerator        hotpSaltGenerator;
    private HOTPProvider         hotpProvider;
    private LockoutStrategy      lockoutStrategy;
    private RegisterUserStrategy registerUserStrategy;
    private PublicKeyCrypto      publicKeyCrypto;
    private HOTPService          hotpService;
    private RoleService          roleService;
    private SubsystemService     subsystemService;
    private Set<String>          notSavedActions = Collections.singleton("action_temp_password");

    private AbstractPolicyValidation<PasswordCheckContext> policyValidation;

    @Required
    public void setPolicyValidation(AbstractPolicyValidation<PasswordCheckContext> policyValidation) {
        this.policyValidation = policyValidation;
    }

    @Required
    public void setUserDao(UserDao userDao) {
        this.userDao = userDao;
    }

    @Required
    public void setSessionDao(SessionDao sessionDao) {
        this.sessionDao = sessionDao;
    }

    @Required
    public void setActionDao(ActionDao actionDao) {
        this.actionDao = actionDao;
    }

    @Required
    public void setNotificationService(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Required
    public void setLoggerSink(LoggerSink loggerSink) {
        this.loggerSink = loggerSink;
    }

    @Required
    public void setPasswordEncoder(PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
    }

    @Required
    public void setSaltSource(SaltSource saltSource) {
        this.saltSource = saltSource;
    }

    @Required
    public void setHotpSaltGenerator(SaltGenerator hotpSaltGenerator) {
        this.hotpSaltGenerator = hotpSaltGenerator;
    }

    @Required
    public void setHotpProvider(HOTPProvider hotpProvider) {
        this.hotpProvider = hotpProvider;
    }

    @Required
    public void setLockoutStrategy(LockoutStrategy lockoutStrategy) {
        this.lockoutStrategy = lockoutStrategy;
    }

    @Required
    public void setRegisterUserStrategy(RegisterUserStrategy registerUserStrategy) {
        this.registerUserStrategy = registerUserStrategy;
    }

    @Required
    public void setPublicKeyCrypto(PublicKeyCrypto publicKeyCrypto) {
        this.publicKeyCrypto = publicKeyCrypto;
    }

    @Required
    public void setHotpService(HOTPService hotpService) {
        this.hotpService = hotpService;
    }

    @Required
    public void setRoleService(RoleService roleService) {
        this.roleService = roleService;
    }

    @Required
    public void setSubsystemService(SubsystemService subsystemService) {
        this.subsystemService = subsystemService;
    }

    public void setNotSavedActions(Set<String> notSavedActions) {
        this.notSavedActions = notSavedActions;
    }

    public SSOUser authenticate(String username, String password, String subsystemIdentifier, String userIpAddress,
                                String sessionInfo) {
        SSOUser ssoUser;
        String  encPassword = passwordEncoder.encode(password, saltSource.getSalt(username));
        AuthSession session = userDao.authenticate(username, encPassword,
                subsystemIdentifier, userIpAddress, sessionInfo);
        boolean ok = session != null && session.getSessionId() != null;
        loggerSink.info(logger, "REMOTE_LOGIN", ok, username);
        if (ok) {
            ssoUser = buildSSOUser(session);
        } else {
            logger.warn("No roles for user {}", username);
            lockoutStrategy.checkLoginsFailed(username, LockoutType.PASSWORD);
            ssoUser = null;
        }
        return ssoUser;
    }

    @Override
    public SSOUser pseudoAuthenticate(String username, String subsystemIdentifier) {
        SSOUser     ssoUser;
        AuthSession session = userDao.pseudoAuthenticate(username, subsystemIdentifier);
        boolean     ok      = session != null && session.getSessionId() != null;
        loggerSink.info(logger, "REMOTE_PSEUDO_LOGIN", ok, username);
        if (ok) {
            ssoUser = buildSSOUser(session);
        } else {
            logger.warn("No roles for user '{}' during pseudo-login", username);
            ssoUser = null;
        }
        return ssoUser;
    }

    private SSOUser buildSSOUser(AuthSession session) {
        SSOUser        ssoUser;
        List<AuthRole> authRoles = session.getRoles();
        if (authRoles.size() == 1 && authRoles.get(0).getRoleName() == null) {
            // actually it's empty
            authRoles = Collections.emptyList();
        }
        Map<SSORole, SSOAction[]> actionsMap = new HashMap<>(authRoles.size());
        for (AuthRole authRole : authRoles) {
            SSORole     ssoRole = new SSORole(authRole.getRoleName());
            SSOAction[] actions = convertToSSOActions(authRole.getActions());
            actionsMap.put(ssoRole, actions);
        }
        Map<String, String> preferences = Collections.emptyMap();
        ssoUser = new SSOUser(session.getUsername(), actionsMap, preferences);
        ssoUser.setSessionId(String.valueOf(session.getSessionId()));
        return ssoUser;
    }

    protected SSOAction[] convertToSSOActions(List<AuthAction> authActions) {
        SSOAction[] actions = new SSOAction[authActions.size()];
        for (int i = 0; i < authActions.size(); i++) {
            AuthAction authAction = authActions.get(i);
            SSOAction  ssoAction  = new SSOAction(authAction.getActionName(), authAction.isLogAction());
            actions[i] = ssoAction;
        }
        return actions;
    }

    public void saveSystemData(String subsystemIdentifier, ActionDescription[] actionDescriptions) {
        List<ActionToSave> actions = convertActionDescriptions(actionDescriptions);
        actionDao.saveActions(subsystemIdentifier, actions);
        if (logger.isDebugEnabled()) {
            logger.debug("Saved actions for subsystem " + subsystemIdentifier + ": " + actions.size());
            logger.debug("Actions are: " + Arrays.asList(actionDescriptions));
        }
    }

    private List<ActionToSave> convertActionDescriptions(ActionDescription[] actionDescriptions) {
        List<ActionToSave> actions = new ArrayList<>(actionDescriptions.length);
        for (ActionDescription description : actionDescriptions) {
            if (!notSavedActions.contains(description.getName().toLowerCase())) {
                ActionToSave action = new ActionToSave();
                action.setName(description.getName());
                action.setDescription(description.getDescription());
                actions.add(action);
            }
        }
        return actions;
    }

    public List<SSOUserWithActions> getUsersWithActions(String subsystemIdentifier) {
        List<UserWithActions>    users  = userDao.getUsersAndActions(subsystemIdentifier);
        List<SSOUserWithActions> result = new ArrayList<>(users.size());
        for (UserWithActions user : users) {
            result.add(convertToSSOUser(user));
        }
        return result;
    }

    public void registerUser(String username, String password, String email, String subsystemIdentifier,
                             RoleGrantSpecification[] roleGrants, String name, String surname, String secretQuestion,
                             String secretAnswer, String publicKey, String organization) throws UserExistsException, PolicyValidationException,
            BadPublicKeyException, MessageSendException {

        UserRegisterRequest registerUser = new UserRegisterRequest();
        registerUser.setUsername(username);
        registerUser.setEmail(email);
        registerUser.setSalt(saltSource.getSalt(username));
        registerUser.setHotpSalt(hotpSaltGenerator.generate());
        registerUser.setPassword(passwordEncoder.encode(password, registerUser.getSalt()));
        registerUser.setPrincipalNames(null);
        registerUser.setSubsystemName(subsystemIdentifier);
        registerUser.setName(name);
        registerUser.setSurname(surname);
        registerUser.setSecretQuestion(secretQuestion);
        registerUser.setSecretAnswer(secretAnswer);
        registerUser.setPublicKey(publicKey);
        registerUser.setOrganization(organization);

        // validate password policy
        policyValidation.validate(new PasswordCheckContext(password, passwordEncoder, userDao
                .getUserPasswordHistoryAndCurrentPassword(username)));

        validatePublicKey(publicKey);

        RoutineResult result = registerUserStrategy.registerUser(registerUser);
        if (result.isOk()) {
            for (RoleGrantSpecification roleGrant : roleGrants) {
                result = userDao.grantRolesToUser(
                        registerUser.getUserid(),
                        roleGrant.isDetectSubsystemIdentifier() ? subsystemIdentifier : roleGrant
                                .getSubsystemIdentifier(), roleGrant.getPrincipalName());
                if (!result.isOk()) {
                    throw new IllegalStateException("Status: " + result.getStatus() + ", errorMessage: "
                            + result.getErrorMessage());
                }
            }

            if (result.isOk()) {
                hotpService.sendTableIfSupported(subsystemIdentifier, registerUser.getUserid());
            }

            notificationService.notifyAboutUsersChanged();
            loggerSink.info(logger, "REGISTER_USER", true, username);
        } else if (result.isDuplicate()) {
            loggerSink.info(logger, "REGISTER_USER", false, username);
            throw new UserExistsException(result.getErrorMessage());
        } else {
            loggerSink.info(logger, "REGISTER_USER", false, username);
            throw new IllegalStateException("Status: " + result.getStatus() + ", errorMessage: "
                    + result.getErrorMessage());
        }
    }

    private void validatePublicKey(String publicKey) throws BadPublicKeyException {
        PGPKeyValidator.validatePublicKey(publicKey, publicKeyCrypto);
    }

    public boolean authenticateHOTP(String subsystemIdentifier, String username, String hotp) {
        boolean ok = hotpProvider.authenticate(subsystemIdentifier, username, hotp);
        if (!ok) {
            userDao.incrementHOTPLoginsFailed(username);
            lockoutStrategy.checkLoginsFailed(username, LockoutType.HOTP);
        } else {
            userDao.clearHOTPLoginsFailed(username);
        }
        loggerSink.info(logger, "REMOTE_HOTP_CHECK", ok, username);
        return ok;
    }

    protected SSOUserWithActions convertToSSOUser(UserWithActions user) {
        return new SSOUserWithActions(user.getUsername(), user.getEmail(), convertToSSOActions(user.getActions()));
    }

    public void changeTempPassword(String userName, String password) throws PolicyValidationException {
        policyValidation.validate(new PasswordCheckContext(password, passwordEncoder, userDao
                .getUserPasswordHistoryAndCurrentPassword(userName)));
        userDao.changeTempPassword(userName, passwordEncoder.encode(password, saltSource.getSalt(userName)));
    }

    public UserForDescription getUserDescription(String username) {
        return userDao.getUserForDescription(username);
    }

    public void updateUserForDescription(UserForDescription user) throws BadPublicKeyException {
        validatePublicKey(user.getPublicKey());
        userDao.updateUserForDescription(user);
    }

    @Override
    public List<UserWithStatus> getUserStatuses(String userNames) {
        return userDao.getUserStatuses(userNames);
    }

    @Override
    public SSOUser exchangeSubsystemToken(String subsystemToken) {
        SSOUser     ssoUser;
        AuthSession session = userDao.exchangeSubsystemToken(subsystemToken);
        boolean     ok      = session != null && session.getSessionId() != null;
        loggerSink.info(logger, "EXCHANGE_SUBSYSTEM_TOKEN", ok, session != null ? session.getUsername() : "TOKEN: " + subsystemToken);
        if (ok) {
            ssoUser = buildSSOUser(session);
        } else {
            if (session != null) {
                logger.warn("No roles for user {}", session.getUsername());
            }
            ssoUser = null;
        }
        return ssoUser;
    }

    @Override
    public void touchSessions(List<Long> sessionIds) {
        if (sessionIds != null && !sessionIds.isEmpty()) {
            if (logger.isDebugEnabled()) {
                logger.debug("Touching sessions " + sessionIds);
            }
            sessionDao.touchSessions(StringUtils.collectionToCommaDelimitedString(sessionIds));
        }
    }

    @Override
    public void completeUser(String username) {
        userDao.completeUser(username);
    }

    @Override
    public void changeUserRole(String username, String newRole, String subsystemIdentifier) {
        final RoutineResult result = userDao.changeUserRole(username, newRole, subsystemIdentifier);
        if (!result.isOk()) {
            throw new IllegalStateException(result.getErrorMessage());
        }
    }

    @Override
    public void createRole(String roleName, String subsystemIdentifier) {
        UISubsystem subsystem = getSubsystem(subsystemIdentifier);

        // Check if role already exists
        UIRole role = roleService.getRoleByName(roleName, subsystem.getId());

        if (role == null) {
            role = new UIRole();
            role.setSubsystemId(subsystem.getId());
            role.setRoleName(roleName);
            role.setPrincipalName(roleName);

            roleService.createRole(role);
        } else {
            if (logger.isDebugEnabled()) {
                logger.debug("Role '" + roleName + "' for subsystem '" + subsystemIdentifier + "' already exists.");
            }
        }
    }

    @Override
    public void mapActionsToRole(List<String> actions, String roleName, String subsystemIdentifier) {
        UISubsystem subsystem = getSubsystem(subsystemIdentifier);

        // Check if role exists
        UIRole role = roleService.getRoleByName(roleName, subsystem.getId());
        if (role == null) {
            throw new IllegalStateException("Role " + roleName + " does not exist.");
        }

        List<UIActionForCheckboxForRole> mappedActions     = roleService.getMappedRoleActions(0, 1000, 1, true, role.getRoleId(), null);
        List<Long>                       actionToAddIds    = new ArrayList<>();
        List<Long>                       actionToRemoveIds = new ArrayList<>();

        for (UIActionForCheckboxForRole mappedAction : mappedActions) {
            if (!actions.contains(mappedAction.getActionName())) {
                // Have to unmap
                actionToRemoveIds.add(mappedAction.getActionId());
            } else {
                // Action already mapped earlier. No need to map it later again.
                actions.remove(mappedAction.getActionName());
            }
        }

        for (String action : actions) {
            UIAction act = actionDao.getActionByNameForSubsystem(action, subsystem.getId());
            if (act != null) {
                actionToAddIds.add(act.getActionId());
            }
        }

        roleService.changeRoleActions(role.getRoleId(), actionToAddIds, actionToRemoveIds);
    }

    @Override
    public SubsystemCreateResponse createSubsystem(String subsystemName, String subsystemTitle, String subsystemUrl, String callbackUrl, String landingUrl) throws SubsystemCreateException {
        String token = subsystemService.generateMainSubsystemToken();

        subsystemTitle = subsystemTitle.replaceAll("[^a-zA-Z0-9.-]", "");
        subsystemName = subsystemName.replaceAll("[. ]", "-");
        subsystemName = subsystemName.toLowerCase().replaceAll("[^a-zA-Z0-9.-]", "");

        if (subsystemName.length() > 32) {
            throw new SubsystemCreateException("Subsystem name '" + subsystemName + "' is too long. Maximum allowed: 32 symbols.");
        }

        // Checking Duplicate entry
        UISubsystem existingSubsystem = subsystemService.getSubsystemByName(subsystemName);
        if (existingSubsystem != null) {
            throw new SubsystemCreateException("Subsystem '" + subsystemName + "' already exists!");
        }

        UISubsystem subsystem = new UISubsystem();
        subsystem.setTitle(subsystemTitle);
        subsystem.setName(subsystemName);
        subsystem.setCallbackUrl(callbackUrl);
        subsystem.setSendCallbacks(true);
        subsystem.setSubsystemUrl(subsystemUrl);
        subsystem.setSubsystemToken(token);
        subsystem.setLandingUrl(landingUrl);

        subsystemService.createSubsystem(subsystem);

        return new SubsystemCreateResponse(subsystemName, token);
    }

    /**
     * @param subsystemIdentifier Subsystem ID
     * @return UISubsystem
     * @throws IllegalStateException when subsystem not found
     */
    private UISubsystem getSubsystem(String subsystemIdentifier) {
        UISubsystem subsystem = subsystemService.getSubsystemByName(subsystemIdentifier);

        if (subsystem == null) {
            throw new IllegalStateException("Subsystem with id " + subsystemIdentifier + " does not exist.");
        }

        return subsystem;
    }
}
