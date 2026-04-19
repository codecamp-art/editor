/******************************************************************************
//系统名:        新交易平台系统
//公司名:
//文件名:        tds_api_error.h
//主要功能:      TDS错误码定义
//说  明:
//用法说明:
//
//修改历史:
******************************************************************************/

#ifndef __TDS_API_ERROR_H__
#define __TDS_API_ERROR_H__

#define TDS_API_GET_VERSION_FAIL_430000101            430000101   //获取TDS API版本信息失败
#define TDS_API_INIT_FAIL_430000102                   430000102   //初始化TDS API失败
#define TDS_API_CREATE_TDS_HANDLE_FAIL_430000103     430000103   //创建TDS句柄失败
#define TDS_API_LOGIN_FAIL_430000104                  430000104   //用户登录失败，可能有多种原因
#define TDS_API_QUERY_FUNC_NO_FAIL_430000105         430000105   //查询主功能号失败
#define TDS_API_INCOMPATIBILITY_ERROR_430000106      430000106   //版本不兼容
#define TDS_API_LOGOUT_FAIL_430000107                430000107   //用户录出失败
#define TDS_API_USER_NOT_LOGIN_ERROR_430000108       430000108   //用户当前未登录
#define TDS_API_QUERY_TRADE_DATE_FAIL_430000109      430000109   //查询交易日期失败
#define TDS_API_QUERY_SERIAL_FAIL_430000110          430000110   //查询流水信息失败
#define TDS_API_QUERY_SNAPSHOT_FAIL_430000111        430000111   //查询快照信息失败
#define TDS_API_SUBSCRIBE_FAIL_430000112             430000112   //订阅失败
#define TDS_API_UNSUBSCRIBE_FAIL_430000113           430000113   //退订失败
#define TDS_API_QUERY_SEAT_FUND_FAIL_851412          430000110   //查询席位资金信息失败

#endif