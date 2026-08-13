package com.tasklinker.api;

/**
 * 排队号信息（自定义 Parcelable），对应实现类为同包下的 TicketInfo.java。
 * 客户端与服务端必须持有"包名 + 类名"完全一致的实现类，
 * 因此与实现类统一放在共享模块 tasklinker-api 中。
 */
parcelable TicketInfo;
