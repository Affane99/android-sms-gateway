package me.capcom.smsgateway.modules.ussd

import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val ussdModule = module {
    single { UssdSettings(get()) }
    singleOf(::UssdService)
}
