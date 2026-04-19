#include <stdio.h>
#include <stdlib.h>
#include <string>
#include <fstream>
#include <iostream>
#include "tds_api.h"
#include "tds_api_define.h"
#include "tds_api_struct_type.h"
#include "threadbase.h"

//#include <windows.h>

using namespace std;

const int req_timeout = 3000000;
int log_level = 2000;
bool klg_enable = true;    //是否把应答数据写入bcLog日志中
int function_no = 20100;

const char *drtp = "10.243.140.173";
const int drtp_port = 6003;
const char *user = "10000";
const char *pwd = "";

static bool s_is_async_req_finished = false;
static int recv_no = -1;

/**
 * 当有返回数据码到达时，会调用该函数
 * @param handle      对应的处理句柄
 * @param data_type   为数据对象类型，具体定义见宏定义
 * @param data_value  收到的数据
 * 注:不能在本函数调用在本头文件内声明的其它功能
 */
void AsyncReq_OnDataReceived(TDS_HANDLE handle, int data_type, char *data)
{
    TTds_Entrust *p_entrust;
    TTds_Cust_Done *p_cust_done;
    TTds_Cust_Hold *p_cust_hold;
    TTds_Cust_Real_Fund *p_cust_real_fund;
    TTds_Trade_Status *p_trade_status;
    TTds_Cust_Fund_Account_Login *p_cust_fund_account_login;

    switch (data_type)
    {
    case TDS_TABLE_ID_ENTRUST: //客户委托信息流水
        p_entrust = (TTds_Entrust *)data;
        printf("客户委托信息：交易日期:%d,交易编码:%s,客户号:%s,委托手数:%d,交割期:%d\n",
               p_entrust->entrust_date,
               p_entrust->tx_no,
               p_entrust->cust_no,
               p_entrust->entrust_qty,
               p_entrust->deliv_date);
        break;

    case TDS_TABLE_ID_CUST_DONE: //客户成交信息
        p_cust_done = (TTds_Cust_Done *)data;
        printf("客户成交信息:交易日期:%d,交易所代码:%s,通道代码:%s,合约代码:%s,品种代码:%s\n",
               p_cust_done->done_date,
               p_cust_done->tx_no,
               p_cust_done->tunnel_code,
               p_cust_done->contract_code,
               p_cust_done->vari_code);
        break;

    case TDS_TABLE_ID_CUST_HOLD: //客户持仓信息
        p_cust_hold = (TTds_Cust_Hold *)data;
        printf("客户持仓信息:交易日期:%d,客户号:%s,客户简称:%s,资金账号:%s,通道代码:%s\n",
               p_cust_hold->tx_date,
               p_cust_hold->cust_no,
               p_cust_hold->cust_name,
               p_cust_hold->fund_account_no,
               p_cust_hold->tunnel_code);
        break;

    case TDS_TABLE_ID_CUST_REAL_FUND: //客户实时资金
        p_cust_real_fund = (TTds_Cust_Real_Fund *)data;
        printf("客户实时资金:交易日期:%d,客户号:%s,资金账号:%s,客户简称:%s\n",
               p_cust_real_fund->trade_date,
               p_cust_real_fund->cust_no,
               p_cust_real_fund->fund_account_no,
               p_cust_real_fund->cust_name);
        break;

    case TDS_TABLE_ID_TRADE_STATUS: //交易状态关系
        p_trade_status = (TTds_Trade_Status *)data;
        printf("交易状态关系：交易日期:%d,交易所代码:%s,通道代码:%s,交易所通道状态:%c,启用标记:%c\n",
               p_trade_status->trade_date,
               p_trade_status->exch_code,
               p_trade_status->tunnel_code,
               p_trade_status->exch_tunnel_status,
               p_trade_status->use_flag);
        break;

    case TDS_TABLE_ID_ACCOUNT_LOGIN: //资金账号登入信息
    {
        p_cust_fund_account_login = (TTds_Cust_Fund_Account_Login *)(void *)data;
        printf("资金账号登入信息:交易日期:%d,客户号:%s,资金账号:%s,登录模块:%c,登录IP地址:%s\n",
               p_cust_fund_account_login->trade_date,
               p_cust_fund_account_login->cust_no,
               p_cust_fund_account_login->fund_account_no,
               p_cust_fund_account_login->login_app,
               p_cust_fund_account_login->login_ip);
    }
    break;

    default:
        printf("获取到数据(handle:%p, datatype:%d)\n", handle, data_type);
        break;
    }

    recv_no++;
}

/**
 * 当没有更多的数据需要提交给用户时，会调用该函数
 * @param handle      对应的处理句柄
 * 注:不能在本函数调用在本头文件内声明的其它功能
 */
void AsyncReq_OnNoMoreData(TDS_HANDLE handle)
{
    s_is_async_req_finished = true;
    printf("数据获取完毕(handle:%p)\n", handle);
}

/**
 * 在数据请求过程中出现问题时，会调用该函数，错误信息见err_code 和err_msg
 * @param handle      对应的处理句柄
 * @param err_code    错误码(-1:获取数据失败; -2:已退出异步通知模式)
 * @param errmsg      错误信息(最大长度为NGTF_MAX_ERR_MSG_LEN)
 * @return:           返回NGTF_FIELD_HANDLE_RESULT(成功返回NGTF_FIELD_HANDLE_SUCCESS, 否则返回其它值)
 * 注:不能在本函数调用在本头文件内声明的其它功能
 */
void AsyncReq_OnError(TDS_HANDLE handle, int err_code, char *err_msg)
{
    printf("请求数据错误(handle:%p, errcode:%d, errmsg:%s)\n", handle, err_code, err_msg);
}

int main()
{
    int err_code;
    char err_msg[256];

    FILE *logfile = fopen("log.txt", "a");
    if (!logfile)
    {
        printf("log error\n");
        return 1;
    }

    /*TDS API初始化 */
    bool ret = TdsApi_init(req_timeout, log_level, klg_enable, function_no, &err_code, err_msg);
    if (!ret)
    {
        printf("初始化错误：%d---%s\n", err_code, err_msg);
    }

    /*增加一个接入点（在调用相关请求功能前必须先调用它），ip为IP地址，port为端口号 */
    if (!TdsApi_addTrtpNode(const_cast<char *>(drtp), drtp_port))
    {
        printf("增加接入点失败\n");
    }

    /* TDS API登录*/
    int rt = TdsApi_reqLogin((char *)user, (char *)pwd, &err_code, err_msg);
    if (rt)
    {
        printf("登录失败：%d---%s\n", err_code, err_msg);
    }

//  //订阅
//  ret = TdsApi_subscribeDataByCust("1411606", &err_code, err_msg);
//  if(!ret)
//  {
//      printf("订阅失败：%d---%s\n", err_code, err_msg);
//  }

    /*TDS API获取API版本号 */
    char version[100];
    TdsApi_getVersion(version, 100);
    printf("API版本号:%s\n", version);

    int data_type; //数据对象类型ID，此ID用来区分流水是哪一张表的流水
    char data[64 * 1024];
    int index = 0;

    TTds_Entrust *p_entrust;
    TTds_Cust_Done *p_cust_done;
    TTds_Cust_Hold *p_cust_hold;
    TTds_Cust_Real_Fund *p_cust_real_fund;
    TTds_Trade_Status *p_trade_status;
    TTds_Cust_Fund_Account_Login *p_cust_fund_account_login;

//  /*TDS API发送交易流水请求 */
//  TDS_HANDLE handle = TdsApi_reqSerial(TDS_SERIAL_ID_TRADE, 0, &err_code, err_msg);
//  if(!handle)
//  {
//      printf("发送交易流水请求错误：%d---%s\n", err_code, err_msg);
//  }

    /*获取交易流水信息（异步） */
    {
        s_is_async_req_finished = false;
        TDS_HANDLE handle = TdsApi_reqSerial(TDS_SERIAL_ID_TRADE, 0, &err_code, err_msg);
        if (handle)
        {
            printf("交易流水信息如下:\n");
            if (!TdsApi_enableAsyncNotify(
                    handle,
                    AsyncReq_OnDataReceived,
                    AsyncReq_OnNoMoreData,
                    AsyncReq_OnError,
                    &err_code,
                    err_msg))
            {
                printf("TdsApi_enableAsyncNotify失败\n");
            }
            else
            {
                //获取门户流水信息
                while (!s_is_async_req_finished)
                {
                    OSSleep(1000); // 1s
                }
            }

            fprintf(logfile, "last received statement's seqno=%d\n", recv_no);
            TdsApi_closeHandle(handle);
        }
        else
        {
            printf("发送交易流水请求错误：%d---%s\n", err_code, err_msg);
        }
    }

//  /*TDS API发送门户流水请求 */
//  {
//      TDS_HANDLE synchandle1 = TdsApi_reqSerial(TDS_SERIAL_ID_PORTAL, 0, &err_code, err_msg);
//      if(!synchandle1)
//      {
//          printf("发送门户流水请求错误：%d---%s\n", err_code, err_msg);
//      }
//
//      printf("门户流水信息如下:\n");
//      /*获取门户流水信息 */
//      while (TdsApi_hasNext(synchandle1, &err_code, err_msg))
//      {
//          ret = TdsApi_getNext(synchandle1, &data_type, data, sizeof(data), &err_code, err_msg);
//          if(ret)
//          {
//              printf("获取门户流水错误：%d---%s\n", err_code, err_msg);
//          }
//
//          switch(data_type)
//          {
//          case TDS_TABLE_ID_ACCOUNT_LOGIN://资金账号登入信息
//              p_cust_fund_account_login = (TTds_Cust_Fund_Account_Login *)data;
//              printf("资金账号登入信息:交易日期:%d,客户号:%s,资金账号:%s,登录模块:%c,登录IP地址:%s\n",
//                     p_cust_fund_account_login->trade_date,
//                     p_cust_fund_account_login->cust_no,
//                     p_cust_fund_account_login->fund_account_no,
//                     p_cust_fund_account_login->login_app,
//                     p_cust_fund_account_login->login_ip);
//              break;
//          default:
//              break;
//          }
//      }
//
//      TdsApi_closeHandle(synchandle1);
//  }

    /*TDS API发送门户流水请求 异步方式 */
//  {
//      s_is_async_req_finished = false;
//      //TDS_HANDLE asynchandle1 = TdsApi_reqSerial(TDS_SERIAL_ID_PORTAL, 0, &err_code, err_msg);
//      TDS_HANDLE asynchandle1 = TdsApi_reqSerial(TDS_SERIAL_ID_TRADE, 1000, &err_code, err_msg);
//      if (asynchandle1)
//      {
//          printf("交易流水信息如下:\n");
//          if (!TdsApi_enableAsyncNotify(
//                  asynchandle1,
//                  AsyncReq_OnDataReceived,
//                  AsyncReq_OnNoMoreData,
//                  AsyncReq_OnError,
//                  &err_code,
//                  err_msg))
//          {
//              printf("TdsApi_enableAsyncNotify失败\n");
//          }
//          else
//          {
//              int loopcount = 0;
//              //获取门户流水信息
//              while (!s_is_async_req_finished)
//              {
//                  Sleep(1000);
//                  if(++loopcount > 3000)
//                  {
//                      break;
//                  }
//              }
//          }
//
//          TdsApi_closeHandle(asynchandle1);
//      }
//      else
//      {
//          printf("发送交易流水请求错误：%d---%s\n", err_code, err_msg);
//      }
//  }

    /*TDS API 获取系统交易日期 */
//  int trade_date;
//  TdsApi_reqTradeDate(&trade_date, &err_code, err_msg);
//  printf("交易日期:%d\n",trade_date);
//
//  int table[6] = {
//      TDS_TABLE_ID_ENTRUST,
//      TDS_TABLE_ID_CUST_DONE,
//      TDS_TABLE_ID_CUST_HOLD,
//      TDS_TABLE_ID_CUST_REAL_FUND,
//      TDS_TABLE_ID_TRADE_STATUS,
//      TDS_TABLE_ID_ACCOUNT_LOGIN};
//
//  printf("快照信息如下:\n");
//
//  for (int i = 0; i < 6; i++)
//  {
//      /*TDS API发送快照请求 */
//      TDS_HANDLE handle0 = TdsApi_reqSnapshot(trade_date, table[i], &err_code, err_msg);
//      /*获取快照信息 */
//      while (TdsApi_hasNext(handle0, &err_code, err_msg))
//      {
//          ret = TdsApi_getNext(handle0, &data_type, data, sizeof(data), &err_code, err_msg);
//          if(ret)
//          {
//              switch(data_type)
//              {
//              case TDS_TABLE_ID_ENTRUST://客户委托信息流水
//                  p_entrust = (TTds_Entrust *)data;
//                  printf("客户委托信息：交易日期:%d,交易编码:%s,委托类型:%c,委托状态:%c,委托方式:%c,订单类型:%c\n",
//                         p_entrust->entrust_date,
//                         p_entrust->tx_no,
//                         p_entrust->entrust_type,
//                         p_entrust->entrust_status,
//                         p_entrust->entrust_way,
//                         p_entrust->order_type);
//                  break;
//              case TDS_TABLE_ID_CUST_DONE://客户成交信息
//                  p_cust_done = (TTds_Cust_Done *)data;
//                  printf("客户成交信息:交易日期:%d,交易所代码:%s,通道代码:%s,合约代码:%s,品种代码:%s\n",
//                         p_cust_done->done_date,
//                         p_cust_done->tx_no,
//                         p_cust_done->tunnel_code,
//                         p_cust_done->contract_code,
//                         p_cust_done->vari_code);
//                  break;
//              case TDS_TABLE_ID_CUST_HOLD://客户持仓信息
//                  p_cust_hold = (TTds_Cust_Hold *)data;
//                  printf("客户持仓信息:交易日期:%d,客户号:%s,客户简称:%s,资金账号:%s,通道代码:%s,每手数量:%d\n",
//                         p_cust_hold->tx_date,
//                         p_cust_hold->cust_no,
//                         p_cust_hold->cust_name,
//                         p_cust_hold->fund_account_no,
//                         p_cust_hold->tunnel_code,
//                         p_cust_hold->hands);
//                  break;
//              case TDS_TABLE_ID_CUST_REAL_FUND://客户实时资金
//                  p_cust_real_fund = (TTds_Cust_Real_Fund *)data;
//                  printf("客户实时资金:交易日期:%d,客户号:%s,资金账号:%s,客户简称:%s\n",
//                         p_cust_real_fund->trade_date,
//                         p_cust_real_fund->cust_no,
//                         p_cust_real_fund->fund_account_no,
//                         p_cust_real_fund->cust_name);
//                  break;
//              case TDS_TABLE_ID_TRADE_STATUS://交易状态关系
//                  p_trade_status = (TTds_Trade_Status *)data;
//                  printf("交易状态关系：交易日期:%d,交易所代码:%s,通道代码:%s,交易所通道状态:%c,启用标记:%c\n",
//                         p_trade_status->trade_date,
//                         p_trade_status->exch_code,
//                         p_trade_status->tunnel_code,
//                         p_trade_status->exch_tunnel_status,
//                         p_trade_status->use_flag);
//                  break;
//              case TDS_TABLE_ID_ACCOUNT_LOGIN://资金账号登入信息
//                  p_cust_fund_account_login = (TTds_Cust_Fund_Account_Login *)data;
//                  printf("资金账号登入信息:交易日期:%d,客户号:%s,资金账号:%s,登录模块:%c,登录IP地址:%s\n",
//                         p_cust_fund_account_login->trade_date,
//                         p_cust_fund_account_login->cust_no,
//                         p_cust_fund_account_login->fund_account_no,
//                         p_cust_fund_account_login->login_app,
//                         p_cust_fund_account_login->login_ip);
//                  break;
//              default:
//                  break;
//              }
//          }
//      }
//
//      TdsApi_closeHandle(handle0);//关闭快照句柄
//  }

    TdsApi_reqLogout(&err_code, err_msg); //TDS API登出

    TdsApi_finalize(); //销毁TDS的初始化
    fclose(logfile);

    return 0;
}