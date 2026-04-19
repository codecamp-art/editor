/******************************************************************************
//系统名:        新交易平台系统
//公司名:        Sungard kingstar
//文件名:        tds_api.h
//主要功能:      TDS接口定义
//说  明:
//用法说明:      err_msg的缓冲区大小建议设为大于等于256，小于256可能引起缓冲区溢出
//
//修改历史:
******************************************************************************/

#ifndef __TDS_API_H
#define __TDS_API_H

#include "tds_api_define.h"
#include "tds_api_struct_type.h"
#include "tds_api_error.h"

#ifdef TDS_API_EXPORTS
#ifdef _WINDOWS
#define TDS_API __declspec(dllexport)
#else
#define TDS_API
#endif
#else
#ifdef _WINDOWS
#define TDS_API __declspec(dllimport)
#else
#define TDS_API
#endif
#endif

typedef void* TDS_HANDLE;

/**
 * 当有返回数据码达时，会调用该函数
 * @param handle      对应的处理句柄
 * @param data_type   为数据对象类型，具体定义见宏定义
 * @param data_value  收到的数据
 * 注:不能在本函数调用在本头文件内声明的其它功能
 */
typedef void (* TdsApiCB_OnDataReceived)(TDS_HANDLE handle, int data_type, char *data_value);

/**
 * 当没有更多的数据需要提交给用户时，会调用该函数
 * @param handle      对应的处理句柄
 * 注:不能在本函数调用在本头文件内声明的其它功能
 */
typedef void (* TdsApiCB_OnNoMoreData)(TDS_HANDLE handle);

/**
 * 在数据请求过程中出现问题时，会调用该函数，错误信息见err_code 和err_msg
 * @param handle      对应的处理句柄
 * @param err_code    错误码(-1:获取数据失败; -2:已退出异步通知模式)
 * @param errmsg      错误信息(最大长度为NGTF_MAX_ERR_MSG_LEN)
 * @return:           返回NGTF_FIELD_HANDLE_RESULT(成功返回NGTF_FIELD_HANDLE_SUCCESS, 否则返回其它值)
 * 注:不能在本函数调用在本头文件内声明的其它功能
 */
typedef void (* TdsApiCB_OnError)(TDS_HANDLE handle, int err_code, char *err_msg);

#ifdef __cplusplus
extern "C" {
#endif

//API函数设计

//获取版本号，version保存返回的版本信息和构建信息，len为version的最大长度（实际数据少于它），成功返回true
bool TDS_API TdsApi_getVersion(char* version, int len);

/*
 * 接口初始化方法
 * @param req_timeout   [IN]: 请求的超时时间
 * @param log_level     [IN]: 日志级别, 大于该级别的日志输出 6000-致命 5000-错误 4000-警告 3000-信息 2000-调试 0-全部
 * @param klg_enable    [BOOL]: 是否把应答数据写入klg false表示不写入
 * @function_no         [IN]: 门户的功能号
 * @param err_code      [OUT]: 错误时，返回的错误码
 * @param err_msg       [OUT]: 错误时，返回的错误信息
 * @return              true表示初始化成功 false表示失败(错误信息见err_code 和err_msg)
 */
bool TDS_API TdsApi_init(int req_timeout, int log_level, bool klg_enable, int function_no, int *err_code, char *err_msg);

//增加一个接入点（在调用相关请求功能前必须先调用它），ip为IP地址，port为端口号，成功返回true
bool TDS_API TdsApi_addDrtpNode(char *ip, int port);

//接口库销毁方法
void TDS_API TdsApi_finalize();

//用户登录方法, 用户名-口令模式, 返回0为成功, 否则错误信息见err_code 和err_msg
int TDS_API TdsApi_reqLogin(char *user_name, char *passwd, int *err_code, char *err_msg);

//用户录出方法, 用户名-口令模式, 返回0为成功, 否则错误信息见err_code 和err_msg
int TDS_API TdsApi_reqLogout(int *err_code, char *err_msg);

//查询当前交易日, 返回0为成功, 否则错误信息见err_code 和err_msg
int TDS_API TdsApi_reqTradeDate(int *trade_date, int *err_code, char *err_msg);

//查询席位资金, 返回0为成功, 否则错误信息见err_code 和err_msg
TDS_HANDLE TDS_API TdsApi_reqSeatFund(char* tunnel_code, char* currency_code, int *err_code, char *err_msg);

//流水请求方法, serial_id为流水ID（如: TDS_SERIAL_ID_TRADE）, seq_no为起始流水序号, 返回空，则处理异常，错误信息见err_code 和err_msg
TDS_HANDLE TDS_API TdsApi_reqSerial(int serial_id, int seq_no, int *err_code, char *err_msg);

//快照请求方法, table_id流水查询表ID（如: TDS_TABLE_ID_ENTRUST）, 返回空，则处理异常，错误信息见err_code 和err_msg
TDS_HANDLE TDS_API TdsApi_reqSnapshot(int trade_date, int table_id, int *err_code, char *err_msg);

//询问后续结果方法，有后续信息返回true，无后续信息或发生错误返回false，错误信息见err_code 和err_msg
bool TDS_API TdsApi_hasNext(TDS_HANDLE handle, int *err_code, char *err_msg);

//获取记录的方法，返回否，则处理异常，data_type为数据对象类型（具体定义见宏定义），data_value为输出结构体的指针，data_size为输出结构体的最大数据长度（实际数据少于它），错误信息见err_code 和err_msg
bool TDS_API TdsApi_getNext(
        TDS_HANDLE handle,
        int *data_type,
        char *data_value,
        int data_size,
        int *err_code,
        char *err_msg);

//使能异步通知功能，这时请不要再使用TdsApi_hasNext、TdsApi_getNext，成功返回true，如果出错，错误信息见err_code 和err_msg
bool TDS_API TdsApi_enableAsyncNotify(
        TDS_HANDLE handle,
        TdsApiCB_OnDataReceived on_data_received,
        TdsApiCB_OnNoMoreData on_no_more_data,
        TdsApiCB_OnError on_error,
        int *err_code,
        char *err_msg);

/**
 * 关闭并销毁请求对象
 * @param handle        [IN]: 处理句柄
 * 注:必须自己销毁相关句柄，在TdsApi_finalize中不会统一销毁
 */
void TDS_API TdsApi_closeHandle(TDS_HANDLE handle);

/**
 * 按客户号订阅数据流
 * @param subscribeCustNoList   [IN]: 订阅的客户号列表，每个客户号用'|'分隔开
 * @param err_code              错误码
 * @param err_msg               错误信息(最大长度为NGTF_MAX_ERR_MSG_LEN)
 * @return:                     成功返回true，失败返回false，错误信息见err_code 和err_msg
 * 注:调用本函数可实现按客户号订阅数据流，不调用此函数时，为全客户数据流
 */
bool TDS_API TdsApi_subscribeDataByCust(
        const char *subscribeCustNoList,
        int *err_code,
        char *err_msg);

/**
 * 按客户号退订数据流，配合订阅数据流函数使用
 * @param unsubscribeCustNoList [IN]: 退订的客户号列表，每个客户号用'|'分隔开
 * @param err_code              错误码
 * @param err_msg               错误信息(最大长度为NGTF_MAX_ERR_MSG_LEN)
 * @return:                     成功返回true，失败返回false，错误信息见err_code 和err_msg
 * 注:本函数配合SubscribeByCust使用，退订无订阅的客户号无意义
 */
bool TDS_API TdsApi_unsubscribeDataByCust(
        const char *unsubscribeCustNoList,
        int *err_code,
        char *err_msg);

#ifdef __cplusplus
}
#endif

#endif // __TDS_API_H
