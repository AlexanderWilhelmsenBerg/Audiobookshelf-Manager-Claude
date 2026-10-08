package com.example.shelfplayer.feature.settings

import com.example.shelfplayer.core.model.AppError
import org.junit.Test
import kotlin.test.assertEquals

class GarminSetupMessageTest {
    @Test fun setupFailureNamesTheActionNeededBeforeAnAccountExists() {
        assertEquals(GarminDeviceMessage.SetupUnavailable, garminFailureMessage(AppError.Network(), true))
        assertEquals(GarminDeviceMessage.InvalidSetup, garminFailureMessage(AppError.Validation("safe"), true))
        assertEquals(GarminDeviceMessage.Failed, garminFailureMessage(AppError.Network(), false))
    }

    @Test fun contentTypeAndSchemaErrorsUseMachineReadableReasons() {
        assertEquals(
            GarminDeviceMessage.ContentType,
            garminFailureMessage(AppError.ApiCompatibility("safe", missingCapability = "sidecar_content_type"), true),
        )
        assertEquals(
            GarminDeviceMessage.IncompatibleSidecar,
            garminFailureMessage(AppError.ApiCompatibility("safe", missingCapability = "sidecar_schema"), true),
        )
        assertEquals(
            GarminDeviceMessage.UpgradeWatch,
            garminFailureMessage(AppError.ApiCompatibility("safe", missingCapability = "provider_setup"), true),
        )
        assertEquals(
            GarminDeviceMessage.Failed,
            garminFailureMessage(AppError.ApiCompatibility("content type is not a reason code"), true),
        )
    }

    @Test fun accountMismatchAndMissingPairingAreDistinctFromPasswordRejection() {
        assertEquals(
            GarminDeviceMessage.AccountMismatch,
            garminFailureMessage(AppError.Authorization("safe", missingPermission = "sidecar_account"), true),
        )
        assertEquals(
            GarminDeviceMessage.WatchLoginRequired,
            garminFailureMessage(AppError.Authorization("safe", missingPermission = "provider_login"), true),
        )
        assertEquals(
            GarminDeviceMessage.PairWatch,
            garminFailureMessage(AppError.Authorization("safe", missingPermission = "provider_pairing"), true),
        )
        assertEquals(
            GarminDeviceMessage.LoginRejected,
            garminFailureMessage(AppError.Authentication(requiresReauthentication = false), true),
        )
    }
}
