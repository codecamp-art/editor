/******************************************************************************
//系统名:        新交易平台系统
//公司名:
//文件名:        tds_api_struct_type.h
//主要功能:      api数据的结构体定义
//说  明:
//用法说明:
//
//修改历史:
******************************************************************************/

#ifndef _TDS_API_STRUCT_TYPE_H
#define _TDS_API_STRUCT_TYPE_H

#include "tds_api_define.h"

/******************************************************************************
//TDS结构体版本号
******************************************************************************/
#define TDS_API_STRUCT_TYPE_VERSION "8.6.0.4"

/******************************************************************************
//客户委托信息结构体
******************************************************************************/
typedef struct _TTds_Entrust
{
    TTDS_DATE_TYPE               entrust_date;         //委托日期
    TTDS_SEQUENCE_NO_TYPE        query_sequence_no;    //当前记录序号
    TTDS_CUST_NO_TYPE            cust_no;              //客户号
    TTDS_FUND_ACCOUNT_NO_TYPE    fund_account_no;      //资金账号
    TTDS_CURRENCY_CODE_TYPE      currency_code;        //币种
    TTDS_TX_NO_TYPE              tx_no;                //交易编码
    TTDS_SEQUENCE_NO_TYPE        inner_entrust_no;     //内部委托号
    TTDS_SEQUENCE_NO_TYPE        entrust_no;           //委托号
    TTDS_EXCH_CODE_TYPE          exch_code;            //交易所代码
    TTDS_TUNNEL_CODE_TYPE        tunnel_code;          //通道代码
    TTDS_ENTRUST_SEAT_NO_TYPE    entrust_seat_no;      //下单席位
    TTDS_ALL_CONTRACT_CODE_TYPE  all_contract_code;    //完整合约代码
    TTDS_VARI_CODE_TYPE          vari_code;            //品种代码
    TTDS_DATE_TYPE               deliv_date;           //交割期
    TTDS_BS_FLAG_TYPE            bs_flag;              //买卖方向
    TTDS_EO_FLAG_TYPE            eo_flag;              //开平方向
    TTDS_SH_FLAG_TYPE            sh_flag;              //投保标记
    TTDS_FORCE_CLOSE_TYPE        force_offset;         //强平标记
    TTDS_ORDER_TYPE              order_type;           //定单类型
    TTDS_DONE_ATTRIBUTE_TYPE     done_attribute;       //成交属性
    TTDS_QTY_TYPE                entrust_qty;          //委托手数
    TTDS_PRICE_TYPE              entrust_price;        //委托价格
    TTDS_PRICE_TYPE              slp_price;           //止损止盈价
    TTDS_QTY_TYPE                done_qty;             //成交手数
    TTDS_PRICE_TYPE              done_price;           //成交价格
    TTDS_QTY_TYPE                remain_qty;           //剩余手数
    TTDS_COMMI_TYPE              frzn_comm;            //冻结手续费
    TTDS_MARGIN_TYPE             frzn_margin;          //冻结保证金
    TTDS_ENTRUST_STATUS_TYPE     entrust_status;       //委托状态
    TTDS_ENTRUST_WAY_TYPE        entrust_way;          //委托方式
    TTDS_ENTRUST_TYPE            entrust_type;         //委托类型
    TTDS_OPER_CODE_TYPE          entrust_oper;         //下单操作员
    TTDS_OPER_CODE_TYPE          cancel_oper;          //撤单操作员
    TTDS_LONG_TIME_TYPE          entrust_time;         //委托时间
    TTDS_LONG_TIME_TYPE          cancel_time;          //撤单时间
    TTDS_LONG_TIME_TYPE          order_time;           //申报时间
    TTDS_LONG_TIME_TYPE          trigger_time;         //触发时间
    TTDS_PRIVATE_SERIAL_NO_TYPE  private_serial_no;    //私有流水号
    TTDS_MIN_DONE_QTY            min_done_qty;         //最小成交数量
    TTDS_IF_MARGIN_DIS_TYPE      if_margin_dis;        //是否上海大边优惠
    TTDS_MARGIN_DISCOUNT_TYPE    margin_discount;      //保证金减免
    TTDS_SYS_NO_TYPE             sys_no;               //系统号
    TTDS_COMBINATION_TYPE        combination_type;     //组合类型
} TTds_Entrust;

/******************************************************************************
//客户非交易委托信息结构体
******************************************************************************/
typedef struct _TTds_Nontrade_Entrust
{
    TTDS_DATE_TYPE               entrust_date;         //委托日期
    TTDS_SEQUENCE_NO_TYPE        query_sequence_no;    //当前记录序号
    TTDS_CUST_NO_TYPE            cust_no;              //客户号
    TTDS_CUST_NAME_TYPE          cust_name;            //客户简称
    TTDS_FUND_ACCOUNT_NO_TYPE    fund_account_no;      //资金账号
    TTDS_CURRENCY_CODE_TYPE      currency_code;        //币种
    TTDS_SEQUENCE_NO_TYPE        entrust_no;           //委托号
    TTDS_SEQUENCE_NO_TYPE        inner_entrust_no;     //内部委托号
    TTDS_EXCH_CODE_TYPE          exch_code;            //交易所代码
    TTDS_TUNNEL_CODE_TYPE        tunnel_code;          //通道代码
    TTDS_VARI_CODE_TYPE          vari_code;            //品种代码
    TTDS_ALL_CONTRACT_CODE_TYPE  all_contract_code;    //完整合约代码
    TTDS_COMMI_TYPE              frzn_comm;            //冻结手续费
    TTDS_MARGIN_TYPE             frzn_margin;          //冻结保证金
    TTDS_ENTRUST_STATUS_TYPE     entrust_status;       //委托状态
    TTDS_ENTRUST_WAY_TYPE        entrust_way;          //委托方式
    TTDS_ENTRUST_TYPE            entrust_type;         //委托类型
    TTDS_OPER_CODE_TYPE          entrust_oper;         //下单操作员
    TTDS_OPER_CODE_TYPE          cancel_oper;          //撤单操作员
    TTDS_LONG_TIME_TYPE          entrust_time;         //委托时间
    TTDS_LONG_TIME_TYPE          cancel_time;          //撤单时间
    TTDS_LONG_TIME_TYPE          order_time;           //申报时间
    TTDS_SYS_NO_TYPE             sys_no;               //系统号
    TTDS_ENTRUST_SEAT_NO_TYPE    entrust_seat_no;      //下单席位
    TTDS_TX_NO_TYPE              tx_no;                //交易编码
    TTDS_NON_TRADE_TYPE          non_trade_type;       //非交易委托类型
    TTDS_QTY_TYPE                entrust_qty;          //委托手数
    TTDS_BS_FLAG_TYPE            bs_flag;              //买卖方向
    TTDS_EO_FLAG_TYPE            eo_flag;              //开平方向
    TTDS_SH_FLAG_TYPE            sh_flag;              //投保标记
    TTDS_IF_NO_TRANS_TYPE        if_no_trans;          //是否免于对冲
    TTDS_IF_KEEP_HOLD_TYPE       if_keep_hold;         //是否保留头寸
    TTDS_CONTRACT_CODE_TYPE      contract_code1;       //合约代码1
    TTDS_CONTRACT_CODE_TYPE      contract_code2;       //合约代码2
    TTDS_BS_FLAG_TYPE            bs_flag1;             //买卖方向1
    TTDS_BS_FLAG_TYPE            bs_flag2;             //买卖方向2
    TTDS_COMB_FLAG_TYPE          comb_flag;            //组合拆分标记
} TTds_Nontrade_Entrust;

/******************************************************************************
//客户成交信息结构体
******************************************************************************/
typedef struct _TTds_Cust_Done
{
    TTDS_DATE_TYPE               done_date;            //成交日期
    TTDS_SEQUENCE_NO_TYPE        query_sequence_no;    //当前记录序号
    TTDS_LONG_TIME_TYPE          done_time;            //成交时间
    TTDS_EXCH_CODE_TYPE          exch_code;            //交易所代码
    TTDS_ENTRUST_SEAT_NO_TYPE    entrust_seat_no;      //下单席位
    TTDS_TUNNEL_CODE_TYPE        tunnel_code;          //通道代码
    TTDS_EXCH_DONE_NO_TYPE       exch_done_no;         //交易所成交号
    TTDS_SYS_NO_TYPE             sys_no;               //系统号
    TTDS_SEQUENCE_NO_TYPE        done_no;              //成交号
    TTDS_SEQUENCE_NO_TYPE        entrust_no;           //委托号
    TTDS_CUST_NO_TYPE            cust_no;              //客户号
    TTDS_CUST_NAME_TYPE          cust_name;            //客户简称
    TTDS_FUND_ACCOUNT_NO_TYPE    fund_account_no;      //资金账号
    TTDS_CURRENCY_CODE_TYPE      currency_code;        //币种
    TTDS_TX_NO_TYPE              tx_no;                //交易编码
    TTDS_CONTRACT_CODE_TYPE      contract_code;        //合约代码
    TTDS_VARI_CODE_TYPE          vari_code;            //品种代码
    TTDS_DATE_TYPE               deliv_date;           //交割期
    TTDS_BS_FLAG_TYPE            bs_flag;              //买卖方向
    TTDS_EO_FLAG_TYPE            eo_flag;              //开平方向
    TTDS_SH_FLAG_TYPE            sh_flag;              //投保标记
    TTDS_QTY_TYPE                done_qty;             //成交手数
    TTDS_PRICE_TYPE              done_price;           //成交价格
    TTDS_COMMI_TYPE              done_comm;            //成交手续费
    TTDS_MARGIN_TYPE             done_margin;          //成交保证金
    TTDS_MARGIN_TYPE             exch_margin;          //交易所保证金
    TTDS_OPER_CODE_TYPE          entrust_oper;         //下单操作员
    TTDS_FORCE_CLOSE_TYPE        force_offset;         //强平标记
    TTDS_MARGIN_DISCOUNT_TYPE    margin_discount;      //保证金减免
} TTds_Cust_Done;

/******************************************************************************
//客户持仓汇总信息结构体
******************************************************************************/
typedef struct _TTds_Cust_Hold
{
    TTDS_DATE_TYPE               tx_date;              //交易日期
    TTDS_SEQUENCE_NO_TYPE        query_sequence_no;    //当前记录序号
    TTDS_CUST_NO_TYPE            cust_no;              //客户号
    TTDS_CUST_NAME_TYPE          cust_name;            //客户简称
    TTDS_FUND_ACCOUNT_NO_TYPE    fund_account_no;      //资金账号
    TTDS_CURRENCY_CODE_TYPE      currency_code;        //币种
    TTDS_TX_NO_TYPE              tx_no;                //交易编码
    TTDS_EXCH_CODE_TYPE          exch_code;            //交易所代码
    TTDS_TUNNEL_CODE_TYPE        tunnel_code;          //通道代码
    TTDS_VARI_CODE_TYPE          vari_code;            //品种代码
    TTDS_CONTRACT_CODE_TYPE      contract_code;        //合约代码
    TTDS_DATE_TYPE               deliv_date;           //交割期
    TTDS_BS_FLAG_TYPE            bs_flag;              //买卖方向
    TTDS_SH_FLAG_TYPE            sh_flag;              //投保标记
    TTDS_QTY_TYPE                hold_qty;             //持仓手数
    TTDS_QTY_TYPE                today_hold_qty;       //今仓手数
    TTDS_QTY_TYPE                hold_normal_qty;      //非组合持仓手数
    TTDS_QTY_TYPE                today_hold_normal_qty;//非组合今仓手数
    TTDS_QTY_TYPE                hands;                //每手数量
    TTDS_PRICE_TYPE              hold_avg_price;       //持仓均价
    TTDS_PRICE_TYPE              open_avg_price;       //开仓均价
    TTDS_PRICE_TYPE              normal_hold_avg_price;//非组合持仓均价
    TTDS_PRICE_TYPE              normal_open_avg_price;//非组合开仓均价
    TTDS_MARGIN_TYPE             margin;               //持仓保证金
    TTDS_MARGIN_TYPE             exch_margin;          //交易所保证金
    TTDS_MARGIN_TYPE             normal_margin;        //非组合持仓保证金
    TTDS_MARGIN_TYPE             normal_exch_margin;   //非组合交易所保证金
    TTDS_QTY_TYPE                frzn_qty;             //冻结总手数
    TTDS_QTY_TYPE                today_frzn_qty;       //今仓冻结手数
    TTDS_MARGIN_DISCOUNT_TYPE    margin_discount;      //保证金减免
    TTDS_QTY_TYPE                hold_tas_qty;         //TAS持仓手数
    TTDS_PRICE_TYPE              tas_hold_avg_price;   //TAS持仓均价
} TTds_Cust_Hold;

/******************************************************************************
//客户实时资金结构体
******************************************************************************/
typedef struct _TTds_Cust_Real_Fund
{
    TTDS_DATE_TYPE               trade_date;           //交易日期
    TTDS_SEQUENCE_NO_TYPE        query_sequence_no;    //当前记录序号
    TTDS_CUST_NO_TYPE            cust_no;              //客户号
    TTDS_FUND_ACCOUNT_NO_TYPE    fund_account_no;      //资金账号
    TTDS_CURRENCY_CODE_TYPE      currency_code;        //币种
    TTDS_CUST_NAME_TYPE          cust_name;            //客户简称
    TTDS_AMT_TYPE                last_remain;          //上日结存
    TTDS_AMT_TYPE                avail_fund;           //可用资金
    TTDS_AMT_TYPE                dyn_rights;           //动态权益
    TTDS_MARGIN_TYPE             b_entrust_frzn;       //买冻结保证金
    TTDS_MARGIN_TYPE             s_entrust_frzn;       //卖冻结保证金
    TTDS_MARGIN_TYPE             b_margin;             //买保证金
    TTDS_MARGIN_TYPE             s_margin;             //卖保证金
    TTDS_MARGIN_TYPE             margin;               //持仓保证金
    TTDS_MARGIN_TYPE             exch_margin;          //交易所保证金
    TTDS_COMMI_TYPE              frzn_comm;            //冻结手续费
    TTDS_AMT_TYPE                total_frzn;           //总冻结
    TTDS_COMMI_TYPE              commi;                //手续费
    TTDS_AMT_TYPE                other_fee;            //其他费用
    TTDS_AMT_TYPE                today_inout;          //今日出入金
    TTDS_AMT_TYPE                drop_profit;          //平仓盈亏
    TTDS_AMT_TYPE                hold_profit;          //浮动盈亏
    TTDS_MARGIN_TYPE             base_margin;          //基础保证金
    TTDS_AMT_TYPE                way_money;            //在途资金
    TTDS_AMT_TYPE                undeliv_profit;       //未交割平仓盈亏
    TTDS_AMT_TYPE                pledge_amt;           //质押金额
    TTDS_AMT_TYPE                credit_amt;           //信用金额
    TTDS_MARGIN_DISCOUNT_TYPE    margin_discount;      //保证金减免
    TTDS_AMT_TYPE                risk_degree0;         //风险度0
    TTDS_AMT_TYPE                risk_degree1;         //客户风险度1
    TTDS_AMT_TYPE                risk_degree2;         //风险度2
    TTDS_AMT_TYPE                risk_degree3;         //风险度3
    TTDS_AMT_TYPE                risk_degree4;         //风险度4
    TTDS_OPER_CODE_TYPE          set_oper_code;        //设置操作员
} TTds_Cust_Real_Fund;

/******************************************************************************
//交易状态关系结构体
******************************************************************************/
typedef struct _TTds_Trade_Status
{
    TTDS_DATE_TYPE               trade_date;           //交易日期
    TTDS_SEQUENCE_NO_TYPE        query_sequence_no;    //当前记录序号
    TTDS_EXCH_CODE_TYPE          exch_code;            //交易所代码
    TTDS_TUNNEL_CODE_TYPE        tunnel_code;          //通道代码
    TTDS_EXCH_TUNNEL_STATUS_TYPE exch_tunnel_status;   //交易所通道状态
    TTDS_EXCH_USE_STATUS_TYPE    use_flag;             //启用标记
} TTds_Trade_Status;

/******************************************************************************
//客户资金账号登入信息结构体
******************************************************************************/
typedef struct _TTds_Cust_Fund_Account_Login
{
    TTDS_DATE_TYPE               trade_date;           //交易日期
    TTDS_SEQUENCE_NO_TYPE        query_sequence_no;    //当前记录序号
    TTDS_CUST_NO_TYPE            cust_no;              //客户号
    TTDS_FUND_ACCOUNT_NO_TYPE    fund_account_no;      //资金账号
    TTDS_LOGIN_MODE_TYPE         login_mode;           //登录模式
    TTDS_OPER_CODE_TYPE          oper_code;            //操作员
    TTDS_OPER_CODE_TYPE          last_oper_code;       //上次代理操作员
    TTDS_IP_ADDR_TYPE            login_ip;             //登录IP地址
    TTDS_IP_ADDR_TYPE            last_login_ip;        //上次登录IP地址
    TTDS_LOGIN_APP_TYPE          login_app;            //登录模块
    TTDS_LOGIN_APP_TYPE          last_login_app;       //上次登录模块
    TTDS_MAC_ADDR_TYPE           login_mac_addr;       //登录网卡地址
    TTDS_MAC_ADDR_TYPE           last_login_mac_addr;  //上次登录网卡地址
    TTDS_DATE_TYPE               login_date;           //登录日期
    TTDS_DATE_TYPE               last_login_date;      //上次登录日期
    TTDS_TIME_TYPE               login_time;           //登录时间
    TTDS_TIME_TYPE               last_login_time;      //上次登录时间
    TTDS_DATE_TYPE               logout_date;          //录出日期
    TTDS_TIME_TYPE               logout_time;          //录出时间
    TTDS_FLAG_TYPE               login_flag;           //登录标识
} TTds_Cust_Fund_Account_Login;

/******************************************************************************
//操作员强制录出结构体
******************************************************************************/
typedef struct _TTds_Force_Oper_Logout
{
    TTDS_DATE_TYPE               logout_date;          //录出日期
    TTDS_SEQUENCE_NO_TYPE        query_sequence_no;    //当前记录序号
    TTDS_OPER_CODE_TYPE          set_oper_code;        //设置操作员
    TTDS_OPER_CODE_TYPE          oper_code;            //强制录出的操作员
    TTDS_LOGIN_APP_TYPE          login_app;            //强制录出模块
    TTDS_FORCE_LOGOUT_REASON_TYPE force_logout_reason; //强制录出原因
    TTDS_TIME_TYPE               logout_time;          //录出时间
} TTds_Force_Oper_Logout;

/******************************************************************************
//期权客户交易级别结构体
******************************************************************************/
typedef struct _TTds_Option_Cust_Trade_Level
{
    TTDS_DATE_TYPE               trade_date;           //交易日期
    TTDS_SEQUENCE_NO_TYPE        query_sequence_no;    //当前记录序号
    TTDS_EXCH_CODE_TYPE          exch_code;            //交易所代码
    TTDS_CUST_NO_TYPE            cust_no;              //客户号
    TTDS_VARI_CODE_TYPE          vari_code;            //品种代码
    TTDS_TRADE_LEVEL_TYPE        trade_level;          //交易级别
    TTDS_OPER_CODE_TYPE          set_oper_code;        //设置操作员
    TTDS_DATE_TYPE               oper_date;            //操作日期
    TTDS_TIME_TYPE               oper_time;            //操作时间
} TTds_Option_Cust_Trade_Level;

/******************************************************************************
//客户交易限制结构体
******************************************************************************/
typedef struct _TTds_Cust_Restricted_Trading
{
    TTDS_DATE_TYPE               trade_date;           //交易日期
    TTDS_SEQUENCE_NO_TYPE        query_sequence_no;    //当前记录序号
    TTDS_CUST_NO_TYPE            cust_no;              //客户号
    TTDS_FUND_ACCOUNT_NO_TYPE    fund_account_no;      //资金账号
    TTDS_STATUS_TYPE             vari_trade_status;    //客户交易限制状态
    TTDS_EXCH_CODE_TYPE          exch_code;            //交易所代码
    TTDS_VARI_CODE_TYPE          vari_code;            //品种代码
    TTDS_DATE_TYPE               deliv_date;           //交割日期
    TTDS_SH_FLAG_TYPE            sh_flag;              //投保标记
    TTDS_OPER_CODE_TYPE          set_oper_code;        //设置操作员
    TTDS_DATE_TYPE               oper_date;            //操作日期
    TTDS_TIME_TYPE               oper_time;            //操作时间
} TTds_Cust_Restricted_Trading;

/******************************************************************************
//操作日志结构体
******************************************************************************/
typedef struct _TTds_Oper_Log
{
    TTDS_DATE_TYPE               trade_date;           //交易日期
    TTDS_SEQUENCE_NO_TYPE        query_sequence_no;    //当前记录序号
    TTDS_SEQUENCE_NO_TYPE        jour_no;              //流水号
    TTDS_SEQUENCE_NO_TYPE        sub_no;               //流水子账号
    TTDS_SEQUENCE_NO_TYPE        func_no;              //功能编号
    TTDS_OPER_CODE_TYPE          oper_code;            //操作员
    TTDS_LOGIN_APP_TYPE          login_app;            //登录模块
    TTDS_CUST_NO_TYPE            cust_no;              //客户号
    TTDS_NOTE_TYPE               oper_note;            //操作描述
    TTDS_LOGIN_STATUS_TYPE       if_success;           //成功标记
    TTDS_DATE_TYPE               oper_date;            //操作日期
    TTDS_TIME_TYPE               oper_time;            //操作时间
} TTds_Oper_Log;

/******************************************************************************
//持仓限制结构体
******************************************************************************/
typedef struct _TTds_Position_Limit
{
    TTDS_DATE_TYPE               trade_date;           //交易日期
    TTDS_SEQUENCE_NO_TYPE        query_sequence_no;    //当前记录序号
    TTDS_CODE_TYPE               market_code;          //市场代码
    TTDS_OBJECT_TYPE             obj_type;             //对象类型
    TTDS_CODE_TYPE               sec_code;             //证券代码
    TTDS_CODE_TYPE               acc_type;             //账户类型
    TTDS_QTY_TYPE                position_limit;       //限仓数量
    TTDS_FLAG_TYPE               protect_flag;         //保护性标记
    TTDS_RANG_TYPE               control_rang;         //控制范围
    TTDS_BS_FLAG_TYPE            direction_type;       //方向
    TTDS_SET_EMP_TYPE            set_emp;              //设置职工
    TTDS_DATE_TYPE               set_date;             //设置日期
    TTDS_TIME_TYPE               set_time;             //设置时间
} TTds_Position_Limit;

/******************************************************************************
//特殊持仓限制结构体
******************************************************************************/
typedef struct _TTds_Spec_Position_Limit
{
    TTDS_DATE_TYPE               trade_date;           //交易日期
    TTDS_SEQUENCE_NO_TYPE        query_sequence_no;    //当前记录序号
    TTDS_CUST_NO_TYPE            cust_no;              //客户号
    TTDS_CODE_TYPE               market_code;          //市场代码
    TTDS_CODE_TYPE               sec_code;             //证券代码
    TTDS_QTY_TYPE                position_limit;       //限仓数量
    TTDS_FLAG_TYPE               protect_flag;         //保护性标记
    TTDS_BS_FLAG_TYPE            direction_type;       //方向
    TTDS_RANG_TYPE               control_rang;         //控制范围
    TTDS_FLAG_TYPE               if_hedging;          //套保套利
    TTDS_SET_EMP_TYPE            set_emp;              //设置职工
    TTDS_DATE_TYPE               set_date;             //设置日期
    TTDS_TIME_TYPE               set_time;             //设置时间
} TTds_Spec_Position_Limit;

/******************************************************************************
//期货合约结构体
******************************************************************************/
typedef struct _TTds_Future_Contract
{
    TTDS_DATE_TYPE               trade_date;           //交易日期
    TTDS_SEQUENCE_NO_TYPE        query_sequence_no;    //当前记录序号
    TTDS_VARI_CODE_TYPE          vari_code;            //品种代码
    TTDS_VARI_NAME_TYPE          vari_name;            //品种名称
    TTDS_DATE_TYPE               deliv_date;           //交割期
    TTDS_CONTRACT_CODE_TYPE      contract_code;        //合约代码
    TTDS_EXCH_CODE_TYPE          exch_code;            //交易所代码
    TTDS_STATUS_TYPE             contract_status;      //合约状态
    TTDS_QTY_TYPE                hands;                //每手数量
    TTDS_PRICE_TYPE              price_unit;           //最小变动价位
    TTDS_QTY_TYPE                max_hold;             //最大持仓数量
    TTDS_QTY_TYPE                min_hand;             //最小下单数量
    TTDS_QTY_TYPE                max_hand;             //最大下单数量
    TTDS_QTY_TYPE                market_max_hand;      //市价最大手数
    TTDS_CURRENCY_CODE_TYPE      currency_code;        //币种
    TTDS_TRADE_RULE_TYPE         trade_rule;           //交易规则
    TTDS_OPER_CODE_TYPE          set_oper_code;        //设置操作员
    TTDS_DATE_TYPE               oper_date;            //操作日期
    TTDS_TIME_TYPE               oper_time;            //操作时间
    TTDS_CONTRACT_NAME_TYPE      contract_name;        //合约名称
    TTDS_CONTRACT_NAME_TYPE      contract_short_name;  //合约简称
    TTDS_PRICE_TYPE              yes_close_price;      //合约前收盘价
    TTDS_PRICE_TYPE              yes_settle_price;     //合约前结算价
    TTDS_PRICE_TYPE              strike_price;         //执行价格
    TTDS_QTY_TYPE                multiplier_unit;      //合约单位
    TTDS_DATE_TYPE               exec_date;            //行权日
    TTDS_DATE_TYPE               expire_date;          //到期日
    TTDS_QTY_TYPE                unover_position_num;  //当前合约未平仓数
    TTDS_PRICE_TYPE              last_vari_price;      //标的证券前收盘价
    TTDS_MARGIN_TYPE             margin_unit;          //单位保证金
    TTDS_MARGIN_RATIO_TYPE       margin_ratio1;        //保证金计算比例参数1
    TTDS_MARGIN_RATIO_TYPE       margin_ratio2;        //保证金计算比例参数2
    TTDS_QTY_TYPE                market_min_hand;      //单笔市价申报下限
    TTDS_CONTRACT_ALL_STATUS     contract_all_status;  //合约所有状态
    TTDS_DATE_TYPE               last_trade_date;      //最后交易日
    TTDS_CHAR                    if_margin_dis;        //保证金优惠标志
    TTDS_CHAR                    option_type;          //期权类型
    TTDS_CHAR                    trade_type;           //交易类型
    TTDS_CHAR                    cp_flag;              //涨跌标志
    TTDS_DATE_TYPE               listing_date;         //上市日期
    TTDS_DOUBLE                  listing_price;        //挂牌价
    TTDS_VARI_CODE_TYPE          underlying_type;      //品种类型
    TTDS_INT4                    update_batch;         //合约变更批次号
    TTDS_INT4                    buy_limit;            //买数量上限
    TTDS_INT4                    sell_limit;           //卖数量上限
    TTDS_CHAR                    if_opt_comb;          //个股组合标志
    TTDS_DATE_TYPE               auto_split_date;      //垂直价差组合策略到期解除日期
    TTDS_DOUBLE                  real_degree;          //实虚程度
    TTDS_CHAR                    if_clear_margin_dis;  //预清算单边优惠是否有效标志
} TTds_Future_Contract;

/******************************************************************************
//实时行情结构体
******************************************************************************/
typedef struct _TTds_Real_Quot
{
    TTDS_DATE_TYPE               trade_date;           //交易日期
    TTDS_SEQUENCE_NO_TYPE        query_sequence_no;    //当前记录序号
    TTDS_EXCH_CODE_TYPE          exch_code;            //交易所代码
    TTDS_TRADE_TYPE              trade_type;           //交易类别
    TTDS_CONTRACT_CODE_TYPE      contract_code;        //合约代码
    TTDS_VARI_CODE_TYPE          vari_code;            //品种代码
    TTDS_DATE_TYPE               deliv_date;           //交割期
    TTDS_PRICE_TYPE              opening_price;        //开盘价
    TTDS_PRICE_TYPE              buy_price;            //买入价
    TTDS_QTY_TYPE                buy_qty;              //买入量
    TTDS_PRICE_TYPE              sell_price;           //卖出价
    TTDS_QTY_TYPE                sell_qty;             //卖出量
    TTDS_PRICE_TYPE              new_price;            //最新价
    TTDS_PRICE_TYPE              top_price;            //最高价
    TTDS_PRICE_TYPE              low_price;            //最低价
    TTDS_QTY_TYPE                done_qty;             //成交手数
    TTDS_PRICE_TYPE              rf_price;             //涨跌
    TTDS_PRICE_TYPE              rlimit_price;         //涨停板
    TTDS_PRICE_TYPE              flimit_price;         //跌停板
    TTDS_PRICE_TYPE              his_top_price;        //历史最高价
    TTDS_PRICE_TYPE              his_low_price;        //历史最低价
    TTDS_QTY_TYPE                net_hold_qty;         //净持仓
    TTDS_PRICE_TYPE              yest_settle_price;    //昨结算
    TTDS_PRICE_TYPE              yest_close_price;     //昨收盘
    TTDS_PRICE_TYPE              today_settle_price;   //今结算
    TTDS_AMT_TYPE                done_amt;             //成交金额
    TTDS_LONG_TIME_TYPE          last_syn_time;        //上次同步时间
    TTDS_STATUS_TYPE             contract_status;      //合约状态
} TTds_Real_Quot;

/******************************************************************************
//现货行情结构体
******************************************************************************/
typedef struct _TTds_Real_Sec_Quot
{
    TTDS_DATE_TYPE               trade_date;           //交易日期
    TTDS_SEQUENCE_NO_TYPE        query_sequence_no;    //当前记录序号
    TTDS_CODE_TYPE               market_code;          //市场代码
    TTDS_CODE_TYPE               sec_code;             //证券代码
    TTDS_PRICE_TYPE              asset_price;          //市值价
    TTDS_PRICE_TYPE              last_price;           //最新价
    TTDS_CURRENCY_CODE_TYPE      currency_code;        //币种
    TTDS_PRICE_TYPE              yest_close_price;     //收盘价
    TTDS_LONG_TIME_TYPE          last_sync_time;       //上次同步时间
} TTds_Real_Sec_Quot;

/******************************************************************************
//档位设置结构体
******************************************************************************/
typedef struct _TTds_Grade_Info
{
    TTDS_DATE_TYPE               trade_date;           //交易日期
    TTDS_SEQUENCE_NO_TYPE        query_sequence_no;    //当前记录序号
    TTDS_CODE_TYPE               grade_code;           //档位代码
    TTDS_GRADE_NAME_TYPE         grade_name;           //档位名称
    TTDS_CUST_ADJUST_RATIO_TYPE  grade_margin_rate;    //档位保证金比例
    TTDS_REMARK_TYPE             remark;               //备注
    TTDS_SET_EMP_TYPE            set_emp;              //设置职工
    TTDS_DATE_TYPE               set_date;             //设置日期
    TTDS_TIME_TYPE               set_time;             //设置时间
} TTds_Grade_Info;

/******************************************************************************
//客户保证金对应关系结构体
******************************************************************************/
typedef struct _TTds_Cust_Margin_Grade
{
    TTDS_DATE_TYPE               trade_date;           //交易日期
    TTDS_SEQUENCE_NO_TYPE        query_sequence_no;    //当前记录序号
    TTDS_CODE_TYPE               market_code;          //市场代码
    TTDS_CODE_TYPE               branch_code;          //营业部
    TTDS_CUST_NO_TYPE            cust_no;              //客户号
    TTDS_CODE_TYPE               grade_code;           //档位代码
    TTDS_CUST_ADJUST_RATIO_TYPE  cust_adjust_ratio;    //保证金调整系数
    TTDS_REMARK_TYPE             remark;               //备注
    TTDS_SET_EMP_TYPE            set_emp;              //设置职工
    TTDS_DATE_TYPE               set_date;             //设置日期
    TTDS_TIME_TYPE               set_time;             //设置时间
} TTds_Cust_Margin_Grade;

/******************************************************************************
//合约保证金调整系数结构体
******************************************************************************/
typedef struct _TTds_Contract_Adjust_Ratio
{
    TTDS_DATE_TYPE               trade_date;           //交易日期
    TTDS_SEQUENCE_NO_TYPE        query_sequence_no;    //当前记录序号
    TTDS_CODE_TYPE               market_code;          //市场代码
    TTDS_CONTRACT_CODE_TYPE      contract_code;        //合约代码
    TTDS_CUST_ADJUST_RATIO_TYPE  contract_adjust_ratio;//保证金调整系数
    TTDS_REMARK_TYPE             remark;               //备注
    TTDS_SET_EMP_TYPE            set_emp;              //设置职工
    TTDS_DATE_TYPE               set_date;             //设置日期
    TTDS_TIME_TYPE               set_time;             //设置时间
} TTds_Contract_Adjust_Ratio;

/******************************************************************************
//通知流水同步结构体
******************************************************************************/
typedef struct _TTds_Notify_Serial_Syn
{
    TTDS_SEQUENCE_NO_TYPE        query_sequence_no;    //当前记录序号
    TTDS_CUST_NO_TYPE            cust_no;              //客户号
    TTDS_CHAR                    send_way;             //发送方式
    TTDS_CHAR                    content_type;         //内容类型
    TTDS_CHAR                    message[801];         //消息内容
    TTDS_DATE_TYPE               date;                 //生成日期
    TTDS_TIME_TYPE               time;                 //生成时间
    TTDS_CHAR                    serial_no[11];        //私有流水号
} TTds_Notify_Serial_Syn;

/******************************************************************************
//席位资金结构体
******************************************************************************/
typedef struct _TTds_Seat_Real_Fund
{
    TTDS_TUNNEL_CODE_TYPE              tunnel_code;            //通道代码
    TTDS_CURRENCY_CODE_TYPE            currency_code;          //币种
    TTDS_AVAIL_MARGIN_BALANCE_TYPE     avail_margin_balance;   //可用保证金余额
    TTDS_TODAY_MARGIN_BALANCE_TYPE     today_margin_balance;   //今日保证金余额
    TTDS_WHOLE_FUND_TYPE               whole_fund;             //总量资金
    TTDS_OCCUPIED_MARGIN_TYPE          occupied_margin;        //已占用保证金
    TTDS_MARGIN_RATE_TYPE              occupied_margin_rate;   //保证金已占用比例
    TTDS_AVAIL_FUND_TYPE               avail_fund;             //可用资金
    TTDS_REMARK_TYPE                   remark;                 //备注信息
} TTds_Seat_Real_Fund;

/******************************************************************************
//出入金流水同步结构体
******************************************************************************/
typedef struct _TTds_Money_IO_Syn
{
    TTDS_DATE_TYPE               tx_date;              //交易日期
    TTDS_SEQUENCE_NO_TYPE        query_sequence_no;    //当前记录序号
    TTDS_SEQUENCE_NO_TYPE        jour_no;              //流水号
    TTDS_SEQUENCE_NO_TYPE        extern_jour_no;       //外部流水号
    TTDS_SEQUENCE_NO_TYPE        orin_jour_no;         //原始流水号
    TTDS_CUST_NO_TYPE            cust_no;              //客户号
    TTDS_FUND_ACCOUNT_NO_TYPE    fund_account_no;      //资金账号
    TTDS_CURRENCY_CODE_TYPE      currency_code;        //币种
    TTDS_MONEY_TYPE              money_type;           //资金类型
    TTDS_MONEY_DIRECTION_TYPE    money_direction;      //出入金方向类型
    TTDS_OCCUR_AMT_TYPE          occur_amt;            //出入金金额类型
    TTDS_MONEY_STATUS_TYPE       money_status;         //出入金状态类型
    TTDS_OPER_CODE_TYPE          set_oper_code;        //设置操作员
    TTDS_DATE_TYPE               oper_date;            //操作日期
    TTDS_TIME_TYPE               oper_time;            //操作时间
    TTDS_OPER_CODE_TYPE          check_oper_code;      //复核操作员
    TTDS_DATE_TYPE               check_oper_date;      //复核日期
    TTDS_TIME_TYPE               check_oper_time;      //复核时间
    TTDS_OPER_CODE_TYPE          reverse_oper_code;    //冲销操作员
    TTDS_DATE_TYPE               reverse_oper_date;    //冲销日期
    TTDS_TIME_TYPE               reverse_oper_time;    //冲销时间
    TTDS_REMARK_TYPE             remark;               //备注
    TTDS_CHAR                    opt_type;             //操作类型
} TTds_Money_IO_Syn;

/******************************************************************************
//期权对冲委托同步结构体
******************************************************************************/
typedef struct _TTds_Self_Close_Entrust_Syn
{
    TTDS_DATE_TYPE               entrust_date;         //委托日期
    TTDS_SEQUENCE_NO_TYPE        query_sequence_no;    //当前记录序号
    TTDS_SEQUENCE_NO_TYPE        serial_no;            //流水号
    TTDS_SEQUENCE_NO_TYPE        entrust_no;           //委托号
    TTDS_SEQUENCE_NO_TYPE        inner_entrust_no;     //内部委托号
    TTDS_QTY_TYPE                entrust_qty;          //委托手数
    TTDS_SYS_NO_TYPE             sys_no;               //系统号
    TTDS_EXCH_CODE_TYPE          exch_code;            //交易所代码
    TTDS_TUNNEL_CODE_TYPE        tunnel_code;          //通道代码
    TTDS_ENTRUST_SEAT_NO_TYPE    entrust_seat_no;      //下单席位
    TTDS_CUST_NO_TYPE            cust_no;              //客户号
    TTDS_TX_NO_TYPE              tx_no;                //交易编码
    TTDS_ALL_CONTRACT_CODE_TYPE  all_contract_code;    //完整合约代码
    TTDS_ENTRUST_STATUS_TYPE     entrust_status;       //委托状态
    TTDS_LONG_TIME_TYPE          entrust_time;         //委托时间
    TTDS_LONG_TIME_TYPE          order_time;           //申报时间
    TTDS_LONG_TIME_TYPE          cancel_time;          //撤单时间
    TTDS_OPER_CODE_TYPE          entrust_oper;         //下单操作员
    TTDS_OPER_CODE_TYPE          cancel_oper;          //撤单操作员
    TTDS_INTFC_TYPE              non_trade_type;       //非交易委托类型
    TTDS_IF_NO_TRANS_TYPE        if_no_trans;          //是否免于对冲
    TTDS_IF_KEEP_HOLD_TYPE       if_keep_hold;         //是否保留头寸
    TTDS_HEDGE_FLAG_TYPE         hedge_flag;           //对冲标识
    TTDS_VARI_CODE_TYPE          vari_code;            //品种代码
    TTDS_CONTRACT_CODE_TYPE      sec_contract_code;    //标的期货合约
} TTds_Self_Close_Entrust_Syn;

/******************************************************************************
//期权对冲流水同步结构体
******************************************************************************/
typedef struct _TTds_Self_Close_Serial_Syn
{
    TTDS_DATE_TYPE               entrust_date;         //委托日期
    TTDS_SEQUENCE_NO_TYPE        query_sequence_no;    //当前记录序号
    TTDS_SEQUENCE_NO_TYPE        serial_no;            //流水号
    TTDS_SEQUENCE_NO_TYPE        entrust_no;           //委托号
    TTDS_QTY_TYPE                entrust_qty;          //委托手数
    TTDS_SYS_NO_TYPE             sys_no;               //系统号
    TTDS_EXCH_CODE_TYPE          exch_code;            //交易所代码
    TTDS_TUNNEL_CODE_TYPE        tunnel_code;          //通道代码
    TTDS_ENTRUST_SEAT_NO_TYPE    entrust_seat_no;      //下单席位
    TTDS_CUST_NO_TYPE            cust_no;              //客户号
    TTDS_TX_NO_TYPE              tx_no;                //交易编码
    TTDS_ALL_CONTRACT_CODE_TYPE  all_contract_code;    //完整合约代码
    TTDS_ENTRUST_STATUS_TYPE     entrust_status;       //委托状态
    TTDS_LONG_TIME_TYPE          entrust_time;         //委托时间
    TTDS_LONG_TIME_TYPE          order_time;           //申报时间
    TTDS_LONG_TIME_TYPE          cancel_time;          //撤单时间
    TTDS_OPER_CODE_TYPE          entrust_oper;         //下单操作员
    TTDS_OPER_CODE_TYPE          cancel_oper;          //撤单操作员
    TTDS_INTFC_TYPE              non_trade_type;       //非交易委托类型
    TTDS_IF_NO_TRANS_TYPE        if_no_trans;          //是否免于对冲
    TTDS_IF_KEEP_HOLD_TYPE       if_keep_hold;         //是否保留头寸
    TTDS_HEDGE_FLAG_TYPE         hedge_flag;           //对冲标识
    TTDS_VARI_CODE_TYPE          vari_code;            //品种代码
    TTDS_CONTRACT_CODE_TYPE      sec_contract_code;    //标的期货合约
} TTds_Self_Close_Serial_Syn;

/******************************************************************************
//实时交易限制同步结构体
******************************************************************************/
typedef struct _TTds_Realtime_Trade_Limit_Syn
{
    TTDS_SERIAL_NO               serial_no;            //业务流水号
    TTDS_EXCH_CODE_TYPE          exch_code;            //交易所代码
    TTDS_TRADE_TYPE              trade_type;           //交易类别
    TTDS_VARI_CODE_TYPE          vari_code;            //品种代码
    TTDS_CONTRACT_CODE_TYPE      contract_code;        //合约代码
    TTDS_FLAG_TYPE               limit_type;           //限制类别
    TTDS_FLAG_TYPE               opt_type;             //操作类型
    TTDS_OPER_CODE_TYPE          oper_code;            //操作员
    TTDS_DATE_TYPE               oper_date;            //操作日期
    TTDS_TIME_TYPE               oper_time;            //操作时间
} TTds_Realtime_Trade_Limit_Syn;

#endif //_TDS_API_STRUCT_TYPE_H