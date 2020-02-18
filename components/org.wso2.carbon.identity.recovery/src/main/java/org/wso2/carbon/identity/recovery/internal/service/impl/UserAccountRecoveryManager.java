/*
 * Copyright (c) 2020, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.carbon.identity.recovery.internal.service.impl;

import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.wso2.carbon.identity.application.common.model.User;
import org.wso2.carbon.identity.core.util.IdentityTenantUtil;
import org.wso2.carbon.identity.core.util.IdentityUtil;
import org.wso2.carbon.identity.governance.service.notification.NotificationChannels;
import org.wso2.carbon.identity.recovery.IdentityRecoveryClientException;
import org.wso2.carbon.identity.recovery.IdentityRecoveryConstants;
import org.wso2.carbon.identity.recovery.IdentityRecoveryException;
import org.wso2.carbon.identity.recovery.IdentityRecoveryServerException;
import org.wso2.carbon.identity.recovery.RecoveryScenarios;
import org.wso2.carbon.identity.recovery.RecoverySteps;
import org.wso2.carbon.identity.recovery.dto.NotificationChannelDTO;
import org.wso2.carbon.identity.recovery.dto.RecoveryChannelInfoDTO;
import org.wso2.carbon.identity.recovery.internal.IdentityRecoveryServiceDataHolder;
import org.wso2.carbon.identity.recovery.model.NotificationChannel;
import org.wso2.carbon.identity.recovery.model.UserRecoveryData;
import org.wso2.carbon.identity.recovery.store.JDBCRecoveryDataStore;
import org.wso2.carbon.identity.recovery.store.UserRecoveryDataStore;
import org.wso2.carbon.identity.recovery.util.Utils;

import org.wso2.carbon.registry.core.utils.UUIDGenerator;
import org.wso2.carbon.user.api.UserStoreException;
import org.wso2.carbon.user.core.UserStoreManager;
import org.wso2.carbon.user.core.service.RealmService;
import org.wso2.carbon.user.core.util.UserCoreUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Manager class which can be used to recover user account with available verified communication channels for a user.
 */
public class UserAccountRecoveryManager {

    private static final Log log = LogFactory.getLog(UserAccountRecoveryManager.class);
    private static UserAccountRecoveryManager instance = new UserAccountRecoveryManager();
    private static final String FORWARD_SLASH = "/";
    private static final NotificationChannels[] notificationChannels = {
            NotificationChannels.EMAIL_CHANNEL, NotificationChannels.SMS_CHANNEL};

    /**
     * Constructor.
     */
    private UserAccountRecoveryManager() {

    }

    /**
     * Get an instance of UserAccountRecoveryManager.
     *
     * @return UserAccountRecoveryManager instance
     */
    public static UserAccountRecoveryManager getInstance() {

        return instance;
    }

    /**
     * Initiate the recovery flow for the user with matching claims.
     *
     * @param claims           User claims
     * @param tenantDomain     Tenant domain
     * @param recoveryScenario Recovery scenario
     * @param properties       Meta properties
     * @return RecoveryChannelInfoDTO object
     */
    public RecoveryChannelInfoDTO retrieveUserRecoveryInformation(Map<String, String> claims, String tenantDomain,
                                                                  RecoveryScenarios recoveryScenario,
                                                                  Map<String, String> properties)
            throws IdentityRecoveryException {

        // Retrieve the user who matches the given set of claims.
        String username = getUsernameByClaims(claims, tenantDomain);
        if (StringUtils.isNotEmpty(username)) {

            // If the account is locked or disabled, do not let the user to recover the account.
            checkAccountLockedStatus(buildUser(username, tenantDomain));
            List<NotificationChannel> notificationChannels;
            // Get the notification management mechanism.
            boolean isNotificationsInternallyManaged = Utils.isNotificationsInternallyManaged(tenantDomain, properties);

            /* If the notification is internally managed, then notification channels available for the user needs to
            be retrieved. If external notifications are enabled, external channel list should be returned.*/
            if (isNotificationsInternallyManaged) {
                notificationChannels = getInternalNotificationChannelList(username, tenantDomain);
            } else {
                notificationChannels = getExternalNotificationChannelList();
            }
            // This flow will be initiated only if the user has any verified channels.
            String recoveryCode = UUIDGenerator.generateUUID();
            return buildUserRecoveryInformationResponseDTO(username, recoveryCode,
                    getNotificationChannelsResponseDTOList(username, recoveryCode, tenantDomain, notificationChannels,
                            recoveryScenario));
        } else {
            if (log.isDebugEnabled()) {
                log.debug("No valid user found for the given claims");
            }
            throw Utils.handleClientException(IdentityRecoveryConstants.ErrorMessages.ERROR_CODE_NO_USER_FOUND, null);
        }
    }

    /**
     * Check whether the account is locked or disabled.
     *
     * @param user User
     * @throws IdentityRecoveryException If account is in locked or disabled status
     */
    private void checkAccountLockedStatus(User user) throws IdentityRecoveryException {

        if (Utils.isAccountDisabled(user)) {
            String errorCode = Utils.prependOperationScenarioToErrorCode(
                    IdentityRecoveryConstants.ErrorMessages.ERROR_CODE_DISABLED_ACCOUNT.getCode(),
                    IdentityRecoveryConstants.USER_ACCOUNT_RECOVERY);
            throw Utils.handleClientException(errorCode,
                    IdentityRecoveryConstants.ErrorMessages.ERROR_CODE_DISABLED_ACCOUNT.getMessage(),
                    user.getUserName());
        } else if (Utils.isAccountLocked(user)) {
            String errorCode = Utils.prependOperationScenarioToErrorCode(
                    IdentityRecoveryConstants.ErrorMessages.ERROR_CODE_LOCKED_ACCOUNT.getCode(),
                    IdentityRecoveryConstants.USER_ACCOUNT_RECOVERY);
            throw Utils.handleClientException(errorCode,
                    IdentityRecoveryConstants.ErrorMessages.ERROR_CODE_LOCKED_ACCOUNT.getMessage(), user.getUserName());
        }
    }

    /**
     * Get the matching username for given claims.
     *
     * @param claims       List of UserClaims
     * @param tenantDomain Tenant domain
     * @return Username (Return null if there are no users)
     * @throws IdentityRecoveryException Error while retrieving the users list
     */
    public String getUsernameByClaims(Map<String, String> claims, String tenantDomain)
            throws IdentityRecoveryException {

        if (MapUtils.isEmpty(claims)) {
            // Get error code with scenario.
            String errorCode = Utils.prependOperationScenarioToErrorCode(
                    IdentityRecoveryConstants.ErrorMessages.ERROR_CODE_NO_FIELD_FOUND_FOR_USER_RECOVERY.getCode(),
                    IdentityRecoveryConstants.USER_ACCOUNT_RECOVERY);
            throw Utils.handleClientException(errorCode,
                    IdentityRecoveryConstants.ErrorMessages.ERROR_CODE_NO_FIELD_FOUND_FOR_USER_RECOVERY.getMessage(),
                    null);
        }
        int tenantId = IdentityTenantUtil.getTenantId(tenantDomain);
        String[] resultedUserList = ArrayUtils.EMPTY_STRING_ARRAY;
        for (String key : claims.keySet()) {
            String value = claims.get(key);
            if (StringUtils.isNotEmpty(key) && StringUtils.isNotEmpty(value)) {
                if (log.isDebugEnabled()) {
                    String message = String.format("Searching users by claim : %s for value : %s", key, value);
                    log.debug(message);
                }
                // Get the matching user list for the given claim.
                String[] matchedUserList = getUserList(tenantId, key, value);
                // Check for duplicates with the already retrieved users, if the matched users for the claim.
                if (!ArrayUtils.isEmpty(matchedUserList)) {
                    // In the first iteration resultedUserList will be empty.
                    if (ArrayUtils.isNotEmpty(resultedUserList)) {
                        // Remove the users from already matched list who are not in the recently matched list.
                        resultedUserList = getCommonUserEntries(resultedUserList, matchedUserList, key, value);
                        if (ArrayUtils.isEmpty(resultedUserList)) {
                            if (log.isDebugEnabled()) {
                                log.debug("No user matched for given claims");
                            }
                            return StringUtils.EMPTY;
                        }
                    } else {
                        resultedUserList = matchedUserList;
                    }
                } else {
                    if (log.isDebugEnabled()) {
                        String message = String.format("No users matched for claim : %s for value : %s", key, value);
                        log.debug(message);
                    }
                    return StringUtils.EMPTY;
                }
            }
        }
        // Return matched user.
        if (ArrayUtils.isNotEmpty(resultedUserList) && resultedUserList.length == 1) {
            return resultedUserList[0];
        } else {
            if (log.isDebugEnabled()) {
                log.debug("Multiple users matched for given claims set : " + Arrays.toString(resultedUserList));
            }
            throw Utils
                    .handleClientException(IdentityRecoveryConstants.ErrorMessages.ERROR_CODE_MULTIPLE_MATCHING_USERS,
                            null);
        }
    }

    /**
     * Get the notification channel list when the notification channel is external.
     *
     * @return External notification channel information
     */
    private List<NotificationChannel> getExternalNotificationChannelList() {

        NotificationChannel channelDataModel = new NotificationChannel();
        channelDataModel.setType(NotificationChannels.EXTERNAL_CHANNEL.getChannelType());
        List<NotificationChannel> notificationChannels = new ArrayList<>();
        notificationChannels.add(channelDataModel);
        return notificationChannels;
    }

    /**
     * Get the notification channel list of the user when the notifications are externally managed.
     *
     * @param username     Username
     * @param tenantDomain Tenant domain
     * @return Notification channel list
     * @throws IdentityRecoveryClientException No notification channels available for the user
     * @throws IdentityRecoveryException       Error getting user claim values
     */
    private List<NotificationChannel> getInternalNotificationChannelList(String username, String tenantDomain)
            throws IdentityRecoveryClientException, IdentityRecoveryException {

        // Create a list of required claims that needs to be retrieved from the user attributes.
        String[] requiredClaims = createRequiredChannelClaimsList();
        // Get channel related claims related to the user.
        Map<String, String> claimValues = getClaimListOfUser(username, tenantDomain, requiredClaims);
        if (MapUtils.isEmpty(claimValues)) {
            throw Utils.handleClientException(
                    IdentityRecoveryConstants.ErrorMessages.ERROR_CODE_NO_NOTIFICATION_CHANNELS_FOR_USER, null);
        }
        // Get the channel list with details.
        List<NotificationChannel> notificationChannels = getNotificationChannelDetails(claimValues);
        if (notificationChannels.size() == 0) {
            throw Utils.handleClientException(
                    IdentityRecoveryConstants.ErrorMessages.ERROR_CODE_NO_VERIFIED_CHANNELS_FOR_USER, null);
        }
        return notificationChannels;
    }

    /**
     * Prepare the response to be sent to the recovery APIs.
     *
     * @param username                Username of the user
     * @param recoveryCode            Recovery code given to the user.
     * @param notificationChannelDTOs List of NotificationChannelsResponseDTOs available for the user.
     * @return RecoveryChannelInfoDTO object
     */
    private RecoveryChannelInfoDTO buildUserRecoveryInformationResponseDTO(String username, String recoveryCode,
                                                                           NotificationChannelDTO[] notificationChannelDTOs) {

        RecoveryChannelInfoDTO recoveryChannelInfoDTO = new RecoveryChannelInfoDTO();
        recoveryChannelInfoDTO.setUsername(username);
        recoveryChannelInfoDTO.setRecoveryCode(recoveryCode);
        recoveryChannelInfoDTO.setNotificationChannelDTOs(notificationChannelDTOs);
        return recoveryChannelInfoDTO;
    }

    /**
     * Get the list of available channels with the channel attributes associated to each channel as a list of
     * NotificationChannelsResponseDTOs.
     *
     * @param userName             UserName of the user
     * @param recoveryID           RecoveryId
     * @param tenantDomain         Tenant domain.
     * @param notificationChannels Notification channels list
     * @param recoveryScenario     Recovery scenario
     * @return NotificationChannelsResponseDTSs list
     */
    private NotificationChannelDTO[] getNotificationChannelsResponseDTOList(String userName, String recoveryID,
                                                                            String tenantDomain,
                                                                            List<NotificationChannel> notificationChannels,
                                                                            RecoveryScenarios recoveryScenario)
            throws IdentityRecoveryException {

        ArrayList<NotificationChannelDTO> notificationChannelDTOs = new ArrayList<>();
        // Store available channels as NotificationChannelDTO objects in the array.
        int channelId = 1;
        StringBuilder recoveryChannels = new StringBuilder();
        for (NotificationChannel channel : notificationChannels) {
            NotificationChannelDTO dto = buildNotificationChannelsResponseDTO(channelId, channel.getType(),
                    channel.getChannelValue(), channel.isPreferredStatus());
            notificationChannelDTOs.add(dto);
            // Creating the notification channel list for recovery.
            String channelEntry = channel.getType() + IdentityRecoveryConstants.CHANNEL_ATTRIBUTE_SEPARATOR + channel
                    .getChannelValue();
            recoveryChannels.append(channelEntry).append(IdentityRecoveryConstants.NOTIFY_CHANNEL_LIST_SEPARATOR);
            channelId++;
        }
        // Notification channel list is stored as the recovery data.
        addRecoveryDataObject(userName, tenantDomain, recoveryID, recoveryScenario, recoveryChannels.toString());
        return notificationChannelDTOs.toArray(new NotificationChannelDTO[0]);
    }

    /**
     * Set notification channel details for each communication channels available for the user.
     *
     * @param channelId   Channel Id
     * @param channelType Channel Type (Eg: EMAIL)
     * @param value       Channel Value (Eg: wso2@gmail.com)
     * @param preference  Whether user marked the channel as a preferred channel of communication
     * @return NotificationChannelDTO object.
     */
    private NotificationChannelDTO buildNotificationChannelsResponseDTO(int channelId, String channelType, String value,
                                                                        boolean preference) {

        NotificationChannelDTO notificationChannelDTO = new NotificationChannelDTO();
        notificationChannelDTO.setId(channelId);
        notificationChannelDTO.setType(channelType);
        // Encode the channel Values.
        if (NotificationChannels.EMAIL_CHANNEL.getChannelType().equals(channelType)) {
            notificationChannelDTO.setValue(maskEmailAddress(value));
        } else if (NotificationChannels.SMS_CHANNEL.getChannelType().equals(channelType)) {
            notificationChannelDTO.setValue(maskMobileNumber(value));
        } else {
            notificationChannelDTO.setValue(value);
        }
        notificationChannelDTO.setPreferred(preference);
        return notificationChannelDTO;
    }

    /**
     * Encode the mobile of the user.
     *
     * @param mobile Mobile number
     * @return Encoded mobile number (Empty String if the user has no mobile number).
     */
    private String maskMobileNumber(String mobile) {

        if (StringUtils.isNotEmpty(mobile)) {
            mobile = mobile.replaceAll(IdentityRecoveryConstants.ChannelMasking.MOBILE_MASKING_REGEX,
                    IdentityRecoveryConstants.ChannelMasking.MASKING_CHARACTER);
        }
        return mobile;
    }

    /**
     * Encode the email address of the user.
     *
     * @param email Email address
     * @return Encoded email address (Empty String if user has no email)
     */
    private String maskEmailAddress(String email) {

        if (StringUtils.isNotEmpty(email)) {
            email = email.replaceAll(IdentityRecoveryConstants.ChannelMasking.EMAIL_MASKING_REGEX,
                    IdentityRecoveryConstants.ChannelMasking.MASKING_CHARACTER);
        }
        return email;
    }

    /**
     * Get the users list for a matching claim.
     *
     * @param tenantId   Tenant ID
     * @param claimUri   Claim to be searched
     * @param claimValue Claim value to be matched
     * @return Matched users list
     * @throws IdentityRecoveryServerException Error while retrieving claims from the userstore manager
     */
    private String[] getUserList(int tenantId, String claimUri, String claimValue)
            throws IdentityRecoveryServerException {

        String[] userList = new String[0];
        UserStoreManager userStoreManager = getUserStoreManager(tenantId);
        try {
            if (userStoreManager != null) {
                if (StringUtils.isNotBlank(claimValue) && claimValue.contains(FORWARD_SLASH)) {
                    String extractedDomain = IdentityUtil.extractDomainFromName(claimValue);
                    UserStoreManager secondaryUserStoreManager = userStoreManager.
                            getSecondaryUserStoreManager(extractedDomain);
                    /*
                    Some claims (Eg:- Birth date) can have "/" in claim values. But in user store level we are trying
                    to extract the claim value and find the user store domain. Hence we are adding an extra "/" to
                    the claim value to avoid such issues.
                     */
                    if (secondaryUserStoreManager == null) {
                        claimValue = FORWARD_SLASH + claimValue;
                    }
                }
                userList = userStoreManager.getUserList(claimUri, claimValue, null);
            }
            return userList;
        } catch (UserStoreException e) {
            if (log.isDebugEnabled()) {
                String error = String
                        .format("Unable to retrieve the claim : %1$s for the given tenant : %2$s", claimUri, tenantId);
                log.debug(error, e);
            }
            throw Utils.handleServerException(
                    IdentityRecoveryConstants.ErrorMessages.ERROR_CODE_ERROR_RETRIEVING_USER_CLAIM, claimUri, e);
        }
    }

    /**
     * Get UserStoreManager.
     *
     * @param tenantId Tenant id
     * @return UserStoreManager object
     * @throws IdentityRecoveryServerException Error getting UserStoreManager
     */
    private UserStoreManager getUserStoreManager(int tenantId) throws IdentityRecoveryServerException {

        UserStoreManager userStoreManager;
        RealmService realmService = IdentityRecoveryServiceDataHolder.getInstance().getRealmService();
        try {
            if (realmService.getTenantUserRealm(tenantId) != null) {
                userStoreManager = (UserStoreManager) realmService.getTenantUserRealm(tenantId).
                        getUserStoreManager();
            } else {
                throw Utils.handleServerException(
                        IdentityRecoveryConstants.ErrorMessages.ERROR_CODE_ERROR_GETTING_USERSTORE_MANAGER, null);
            }
        } catch (UserStoreException e) {
            if (log.isDebugEnabled()) {
                String error = String.format("Error retrieving the user store manager for the tenant : %s", tenantId);
                log.debug(error, e);
            }
            throw Utils.handleServerException(
                    IdentityRecoveryConstants.ErrorMessages.ERROR_CODE_ERROR_GETTING_USERSTORE_MANAGER, null, e);
        }
        return userStoreManager;
    }

    /**
     * Keep the common users list from the previously matched list and the new list.
     *
     * @param resultedUserList Already matched users for previous claims
     * @param matchedUserList  Retrieved users list for the given claim
     * @param claim            Claim used for filtering
     * @param value            Value given for the claim
     * @return Users list with no duplicates
     */
    private String[] getCommonUserEntries(String[] resultedUserList, String[] matchedUserList, String claim,
                                          String value) {

        ArrayList<String> matchedUsers = new ArrayList<>(Arrays.asList(matchedUserList));
        ArrayList<String> resultedUsers = new ArrayList<>(Arrays.asList(resultedUserList));
        // Remove not matching users.
        resultedUsers.retainAll(matchedUsers);
        if (resultedUsers.size() > 0) {
            resultedUserList = resultedUsers.toArray(new String[0]);
            if (log.isDebugEnabled()) {
                log.debug("Current matching temporary user list :" + Arrays.toString(resultedUserList));
            }
            return resultedUserList;
        } else {
            if (log.isDebugEnabled()) {
                String message = String
                        .format("There are no common users for claim : %1$s with the value : %2$s with the "
                                + "previously filtered user list", claim, value);
                log.debug(message);
            }
            return new String[0];
        }
    }

    /**
     * Get claim values of a user for a given list of claims.
     *
     * @param username          Username of the user
     * @param tenantDomain      tenant domain
     * @param requiredClaimURLs Claims that needs to be retrieved.
     * @return Map of claims and values
     * @throws IdentityRecoveryException Error while getting the user claims of the user
     */
    private Map<String, String> getClaimListOfUser(String username, String tenantDomain, String[] requiredClaimURLs)
            throws IdentityRecoveryException {

        int tenantId = IdentityTenantUtil.getTenantId(tenantDomain);
        UserStoreManager userStoreManager = getUserStoreManager(tenantId);
        Map<String, String> claimValues = null;
        try {
            if (userStoreManager != null) {
                claimValues = userStoreManager.getUserClaimValues(username, requiredClaimURLs, null);
            }
        } catch (UserStoreException e) {
            String error = String
                    .format("Error getting claims of user : %1$s in tenant domain : %2$s", username, tenantDomain);
            if (log.isDebugEnabled()) {
                log.debug(error, e);
            }
            throw Utils
                    .handleServerException(IdentityRecoveryConstants.ErrorMessages.ERROR_CODE_ERROR_LOADING_USER_CLAIMS,
                            null);
        }
        return claimValues;
    }

    /**
     * Create required claim list from the attributes in the Notification channel list. The required claims will be
     * used to get user's attributes.
     *
     * @return Required claims list
     */
    private String[] createRequiredChannelClaimsList() {

        List<String> requiredClaims = new ArrayList<>();
        for (NotificationChannels channel : notificationChannels) {
            requiredClaims.add(channel.getClaimUri());
            requiredClaims.add(channel.getVerifiedClaimUrl());
        }
        requiredClaims.add(IdentityRecoveryConstants.PREFERRED_CHANNEL_CLAIM);
        // Get the list of roles that the user has since the channel selection criteria changes with the availability
        // of INTERNAL/selfsignup role.
        requiredClaims.add(IdentityRecoveryConstants.USER_ROLES_CLAIM);
        return requiredClaims.toArray(new String[0]);
    }

    /**
     * get the available verified Notification channel objects.
     *
     * @param claimValues Claim values related to the notification channels
     * @return Verified notification channels for the user.
     */
    private List<NotificationChannel> getNotificationChannelDetails(Map<String, String> claimValues) {

        // Check whether the user is self registered user.
        boolean isSelfRegisteredUser = isSelfSignUpUser(claimValues.get(IdentityRecoveryConstants.USER_ROLES_CLAIM));
        String preferredChannel = claimValues.get(IdentityRecoveryConstants.PREFERRED_CHANNEL_CLAIM);
        List<NotificationChannel> verifiedChannels = new ArrayList<>();
        for (NotificationChannels channel : notificationChannels) {
            String channelValue = claimValues.get(channel.getClaimUri());
            boolean channelVerified = Boolean.parseBoolean(claimValues.get(channel.getVerifiedClaimUrl()));
            NotificationChannel channelDataModel = new NotificationChannel();

            // If the user is self registered, then user has to have the verified channel claims. Check whether channel
            // is verified and not empty.
            if (isSelfRegisteredUser && channelVerified && StringUtils.isNotEmpty(channelValue)) {
                channelDataModel.setType(channel.getChannelType());
                channelDataModel.setChannelValue(channelValue);
                // Check whether the preferred channel matches the given channel.
                if (StringUtils.isNotEmpty(preferredChannel) && channel.getChannelType().equals(preferredChannel)) {
                    channelDataModel.setPreferredStatus(true);
                }
                verifiedChannels.add(channelDataModel);
            } else if (StringUtils.isNotEmpty(channelValue)) {
                channelDataModel.setType(channel.getChannelType());
                channelDataModel.setChannelValue(channelValue);
                // Check whether the preferred channel matches the given channel.
                if (StringUtils.isNotEmpty(preferredChannel) && channel.getChannelType().equals(preferredChannel)) {
                    channelDataModel.setPreferredStatus(true);
                }
                verifiedChannels.add(channelDataModel);
            }
        }
        return verifiedChannels;
    }

    /**
     * Checks whether the user is a self signed-up user or not.
     *
     * @param rolesList Roles that the user has
     * @return TRUE of the user has self sign-up role
     */
    private boolean isSelfSignUpUser(String rolesList) {

        List<String> roles = Arrays.asList(rolesList.split(IdentityRecoveryConstants.SIGN_UP_ROLE_SEPARATOR));
        return roles.contains(IdentityRecoveryConstants.SELF_SIGNUP_ROLE);
    }

    /**
     * Validate the code.
     *
     * @param code Code given for recovery
     * @param step Recovery step
     * @throws IdentityRecoveryException Error validating the recoveryId
     */
    public UserRecoveryData getUserRecoveryData(String code, RecoverySteps step) throws IdentityRecoveryException {

        UserRecoveryData recoveryData;
        UserRecoveryDataStore userRecoveryDataStore = JDBCRecoveryDataStore.getInstance();
        try {
            // Retrieve recovery data bound to the recoveryId.
            recoveryData = userRecoveryDataStore.load(code);
        } catch (IdentityRecoveryException e) {
            // Map code expired error to new error codes for user account recovery.
            if (IdentityRecoveryConstants.ErrorMessages.ERROR_CODE_INVALID_CODE.getCode().equals(e.getErrorCode())) {
                e.setErrorCode(IdentityRecoveryConstants.ErrorMessages.ERROR_CODE_INVALID_RECOVERY_CODE.getCode());
            } else if (IdentityRecoveryConstants.ErrorMessages.ERROR_CODE_EXPIRED_CODE.getCode()
                    .equals(e.getErrorCode())) {
                e.setErrorCode(IdentityRecoveryConstants.ErrorMessages.ERROR_CODE_EXPIRED_RECOVERY_CODE.getCode());
            } else {
                e.setErrorCode(Utils.prependOperationScenarioToErrorCode(e.getErrorCode(),
                        IdentityRecoveryConstants.USER_ACCOUNT_RECOVERY));
            }
            throw e;
        }
        if (recoveryData == null) {
            throw Utils
                    .handleClientException(IdentityRecoveryConstants.ErrorMessages.ERROR_CODE_NO_ACCOUNT_RECOVERY_DATA,
                            code);
        }
        if (!step.equals(recoveryData.getRecoveryStep())) {
            throw Utils.handleClientException(IdentityRecoveryConstants.ErrorMessages.ERROR_CODE_INVALID_RECOVERY_CODE,
                    code);
        }
        return recoveryData;
    }

    /**
     * Add the notification channel recovery data to the store.
     *
     * @param username     Username
     * @param tenantDomain Tenant domain
     * @param secretKey    RecoveryId
     * @param scenario     RecoveryScenario
     * @param recoveryData Data to be stored as mata which are needed to evaluate the recovery data object
     * @throws IdentityRecoveryServerException Error storing recovery data
     */
    private void addRecoveryDataObject(String username, String tenantDomain, String secretKey,
                                       RecoveryScenarios scenario, String recoveryData)
            throws IdentityRecoveryServerException {

        // Create a user object.
        User user = buildUser(username, tenantDomain);
        UserRecoveryData recoveryDataDO = new UserRecoveryData(user, secretKey, scenario,
                RecoverySteps.SEND_RECOVERY_INFORMATION);
        // Store available channels in remaining setIDs.
        recoveryDataDO.setRemainingSetIds(recoveryData);
        try {
            UserRecoveryDataStore userRecoveryDataStore = JDBCRecoveryDataStore.getInstance();
            userRecoveryDataStore.invalidate(user);
            userRecoveryDataStore.store(recoveryDataDO);
        } catch (IdentityRecoveryException e) {
            throw Utils.handleServerException(
                    IdentityRecoveryConstants.ErrorMessages.ERROR_CODE_ERROR_STORING_RECOVERY_DATA,
                    "Error Storing Recovery Data", e);
        }
    }

    /**
     * Build a User object.
     *
     * @param username     Username of the user
     * @param tenantDomain Tenant domain of the user
     * @return User
     */
    private User buildUser(String username, String tenantDomain) {

        User user = new User();
        user.setUserName(UserCoreUtil.removeDomainFromName(username));
        user.setTenantDomain(tenantDomain);
        user.setUserStoreDomain(IdentityUtil.extractDomainFromName(username));
        return user;
    }
}
