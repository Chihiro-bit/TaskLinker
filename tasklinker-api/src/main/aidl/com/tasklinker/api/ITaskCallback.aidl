package com.tasklinker.api;

import com.tasklinker.api.TicketInfo;

/**
 * 客户端注册到服务端的排队号状态回调。
 *
 * oneway 单向异步事务：服务端广播不会被某个响应缓慢/卡死的客户端阻塞，
 * 单个客户端异常不会拖垮对全体客户端（大屏/医生站）的通知。
 * 失效回调的清理依赖 RemoteCallbackList 内部的死亡监听。
 */
oneway interface ITaskCallback {

    /**
     * 排队号状态变化（取号排队 / 叫号 / 就诊完成 / 过号 / 退号）。
     * 具体状态见 TicketInfo.state（TicketState）。
     */
    void onTicketStateChanged(in TicketInfo ticket);
}
