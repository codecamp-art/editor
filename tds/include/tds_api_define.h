/******************************************************************************
//系统名:        新交易平台系统
//文件名:        tds_api_define.h
//主要功能:      TDS宏和类型定义
//说  明:
//用法说明:
//
//修改历史:
******************************************************************************/

#ifndef _TDS_API_DEFINE_H
#define _TDS_API_DEFINE_H

/******************************************************************************
//流水ID
******************************************************************************/

#define TDS_SERIAL_ID_TRADE                  0   //交易流水类型
#define TDS_SERIAL_ID_PORTAL                 1   //门户流水类型

/******************************************************************************
//流水查询表ID
******************************************************************************/

#define TDS_TABLE_ID_ENTRUST                 0   //客户委托信息
#define TDS_TABLE_ID_CUST_DONE              1   //客户成交信息
#define TDS_TABLE_ID_CUST_HOLD              2   //客户持仓汇总信息
#define TDS_TABLE_ID_CUST_REAL_FUND         3   //客户实时资金
#define TDS_TABLE_ID_TRADE_STATUS           4   //交易状态关系
#define TDS_TABLE_ID_ACCOUNT_LOGIN          5   //资金账号登入信息
#define TDS_TABLE_ID_OPER_FORCE_LOGOUT      6   //强制录出操作员
#define TDS_TABLE_ID_OPTION_CUST_LEVEL      7   //期权客户级别
#define TDS_TABLE_ID_CUST_VARI_RESTRICTED   8   //客户交易受限
#define TDS_TABLE_ID_OPER_LOG               9   //系统操作日志
#define TDS_TABLE_ID_POSITION_LMT_MODEL_INFO 10 //限仓信息
#define TDS_TABLE_ID_CUST_SPEC_POSITION_LMT 11  //客户特殊限仓
#define TDS_TABLE_ID_FTR_CONTRACT           12  //期货合约
#define TDS_TABLE_ID_REAL_QUOT              13  //实时行情
#define TDS_TABLE_ID_REAL_SEC_QUOT          14  //现货行情
#define TDS_TABLE_ID_GRADE_INFO             15  //档位设置
#define TDS_TABLE_ID_CUST_MARGIN_GRADE      16  //客户保证金对应关系
#define TDS_TABLE_ID_CONTRACT_ADJUST_RATIO  17  //合约保证金调整系数
#define TDS_TABLE_ID_NOTIFY_SERIAL_SYN      18  //通知流水同步
#define TDS_TABLE_ID_NONTRADE_ENTRUST       19  //客户非交易委托信息
#define TDS_TABLE_ID_SEAT_REAL_FUND         20  //席位资金信息
#define TDS_TABLE_ID_MONEY_IO_SYN           21  //出入金流水同步
#define TDS_TABLE_ID_SELF_CLOSE_ENTRUST_SYN 22  //期权对冲委托同步
#define TDS_TABLE_ID_SELF_CLOSE_SERIAL_SYN  23  //期权对冲流水同步
#define TDS_TABLE_ID_REALTIME_TRADE_LIMIT_SYN 24 //实时交易限制同步

/******************************************************************************
//基本数据类型
******************************************************************************/

typedef char            TTDS_CHAR;
typedef int             TTDS_INT4;
typedef unsigned char   TTDS_BYTE;
typedef unsigned int    TTDS_DWORD;
typedef double          TTDS_DOUBLE;

/******************************************************************************
//业务类型
******************************************************************************/

//买卖方向类型
typedef TTDS_CHAR TTDS_BS_FLAG_TYPE;

#define TDS_BUY_DIRECTION                    '0' //买
#define TDS_SELL_DIRECTION                   '1' //卖

//开平方向类型
typedef TTDS_CHAR TTDS_EO_FLAG_TYPE;

#define TDS_OPEN_DIRECTION                   '0' //开
#define TDS_OFFSET_DIRECTION                 '1' //平
#define TDS_OFFSET_TODAY_DIRECTION           '3' //平今

//投保标记类型
typedef TTDS_CHAR TTDS_SH_FLAG_TYPE;

#define TDS_SPEC_DIRECTION                   '0' //投机非备兑
#define TDS_HEDGE_DIRECTION                  '1' //保值非备兑
#define TDS_ARBIT_DIRECTION                  '2' //套利非备兑

//组合投保类型
typedef TTDS_CHAR TTDS_COMB_SH_TYPE;

#define TDS_COMB_SH_SPEC_SPEC                '0' //投机-投机
#define TDS_COMB_SH_HEDGE_HEDGE              '1' //保值-保值
#define TDS_COMB_SH_HEDGE_SPEC               '6' //保值-投机
#define TDS_COMB_SH_SPEC_HEDGE               '7' //投机-保值

//强平标记类型
typedef TTDS_CHAR TTDS_FORCE_CLOSE_TYPE;

#define TDS_MANUAL_FORCE_CLOSE               '0' //手工录入强平
#define TDS_RISK_FORCE_CLOSE                 '1' //风控终端强平
#define TDS_AUTO_FORCE_CLOSE                 '2' //自动强平

//定单类型
typedef TTDS_CHAR TTDS_ORDER_TYPE;

#define TDS_LIMIT_PRICE                      '0' //限价
#define TDS_MARKET_PRICE                     '1' //市价
#define TDS_BEST_PRICE                       '2' //最优价
#define TDS_MARKET_STOP_LOST                 '3' //市价止损
#define TDS_MARKET_STOP_PROFIT               '4' //市价止赢
#define TDS_LIMIT_STOP_LOST                  '5' //限价止损
#define TDS_LIMIT_STOP_PROFIT                '6' //限价止赢

//成交属性类型
typedef TTDS_CHAR TTDS_DONE_ATTRIBUTE_TYPE;

#define TDS_GFD_ATTRIBUTE                    '0' //GFD当日有效
#define TDS_FOK_ATTRIBUTE                    '1' //FOK全成或全撤
#define TDS_FAK_ATTRIBUTE                    '2' //FAK剩余即撤销

//委托状态类型
typedef TTDS_CHAR TTDS_ENTRUST_STATUS_TYPE;

#define TDS_ENTRUST_STATUS_WAIT_SEND         'n' //等待发出
#define TDS_ENTRUST_STATUS_SENDING           's' //正在申报
#define TDS_ENTRUST_STATUS_SENDED            'a' //已经报入
#define TDS_ENTRUST_STATUS_PART_MATCHED      'p' //部分成交
#define TDS_ENTRUST_STATUS_ALL_MATCHED       'c' //全部成交
#define TDS_ENTRUST_STATUS_PART_CANCEL       'b' //部分部撤
#define TDS_ENTRUST_STATUS_WAIT_CANCEL       'f' //等待撤除
#define TDS_ENTRUST_STATUS_HAS_CANCELED      'd' //已经撤消
#define TDS_ENTRUST_STATUS_WRONG             'e' //错误委托
#define TDS_ENTRUST_STATUS_REFUSE            'q' //场内拒绝

//非交易委托类型
typedef TTDS_CHAR TTDS_NONTRADE_ENTRUST_WAY_TYPE;

//非交易委托状态
typedef TTDS_CHAR TTDS_NONTRADE_ENTRUST_TYPE;

//委托方式类型
typedef TTDS_CHAR TTDS_ENTRUST_WAY_TYPE;

#define TDS_COUNTER_ENTRUST                  '1' //柜台委托
#define TDS_DOS_ENTRUST                      '2' //DOS敲套
#define TDS_REMOTE_ENTRUST                   '3' //远程委托
#define TDS_WEB_ENTRUST                      '4' //网上交易
#define TDS_PHONE_ENTRUST                    '5' //电话委托
#define TDS_OTHER_ENTRUST                    '6' //其他委托
#define TDS_THIRD_PARTY_ENTRUST              '7' //第三方委托
#define TDS_CUST_ENTRUST                     '8' //客户委托

//委托类型
typedef TTDS_CHAR TTDS_ENTRUST_TYPE;

#define TDS_ENTRUST_NORMAL                   'A' //普通委托
#define TDS_ENTRUST_BAT                      'B' //批量委托
#define TDS_ENTRUST_PRE_NORMAL               'D' //预埋普通委托
#define TDS_ENTRUST_PRE_NORMAL_BAT           'E' //预埋批量委托
#define TDS_ENTRUST_COMBO                    'J' //组合委托
#define TDS_ENTRUST_COMBO_BAT                'K' //组合批量委托
#define TDS_ENTRUST_PRE_COMBO                'L' //预埋组合委托
#define TDS_ENTRUST_PRE_COMBO_BAT            'M' //预埋组合批量委托

//交易所通道交易状态类型
typedef TTDS_CHAR TTDS_EXCH_TUNNEL_STATUS_TYPE;

#define TDS_EXCH_INIT_COMP                   '7' //初始化完成
#define TDS_EXCH_OPEN                        '0' //开市
#define TDS_EXCH_OPENING                     '1' //开盘
#define TDS_EXCH_CLOSING                     '2' //收盘
#define TDS_EXCH_CLOSE                       '3' //收市
#define TDS_EXCH_PAUSE                       '4' //暂停
#define TDS_EXCH_CONTINUE                    '5' //继续

//登录模式类型
typedef TTDS_CHAR TTDS_LOGIN_MODE_TYPE;

#define TTDS_LOGIN_MODE_TYPE_TRADE           '0' //交易模式
#define TTDS_LOGIN_MODE_TYPE_QUERY           '1' //查询模式

//交易所通道启功状态类型
typedef TTDS_CHAR TTDS_EXCH_USE_STATUS_TYPE;

#define TDS_EXCH_TUNNEL_DISABLE              '0' //停用
#define TDS_EXCH_TUNNEL_ENABLE               '1' //启动

//登录状态类型
typedef TTDS_CHAR TTDS_LOGIN_STATUS_TYPE;

#define TDS_LOGIN_SUCCESS                    '1' //登录成功
#define TDS_LOGIN_FALSE                      '0' //登录失败

//非交易委托类型
typedef TTDS_CHAR TTDS_NON_TRADE_TYPE;

#define TDS_NON_TRADE_TYPE_XQ                '1' //行权
#define TDS_NON_TRADE_TYPE_FQXQ              '2' //放弃行权
#define TDS_NON_TRADE_TYPE_COMB              '7' //组合申请
#define TDS_NON_TRADE_TYPE_SPLIT             '8' //组合拆分

//是否免于对冲类型
typedef TTDS_CHAR TTDS_IF_NO_TRANS_TYPE;

#define TDS_IF_NO_TRANS_TYPE_NO              '0' //不免于对冲
#define TDS_IF_NO_TRANS_TYPE_YES             '1' //免于对冲

//是否保留头寸类型
typedef TTDS_CHAR TTDS_IF_KEEP_HOLD_TYPE;

#define TDS_IF_KEEP_HOLD_TYPE_NO             '0' //不保留头寸
#define TDS_IF_KEEP_HOLD_TYPE_YES            '1' //保留头寸

//对冲标识类型
typedef TTDS_CHAR TTDS_HEDGE_FLAG_TYPE;

#define TDS_HEDGE_FLAG_TYPE_NO               '0' //不对冲
#define TDS_HEDGE_FLAG_TYPE_YES              '1' //对冲

//期权对冲类型
typedef TTDS_CHAR TTDS_INTFC_TYPE;

#define TDS_INTFC_SELFCLOSE_PREEXEC          '1' //期权对冲申请：执行前对冲
#define TDS_INTFC_MARKETMAKER_RSRV           '2' //期权对冲申请：做市商留仓
#define TDS_INTFC_SELFCLOSE_AFTEXEC          '3' //期权对冲申请：(卖方)履约后对冲
#define TDS_INTFC_CANCEL_AUTO_EXEC           '4' //大连交易所：取消到日期自动行权
#define TDS_INTFC_SELFCLOSE_AFTEXEC_BUY      '5' //期权对冲申请：(买方)行权后对冲
#define TDS_INTFC_SELFCLOSE_FUTURES          '6' //大连交易所：期货对冲

//组合拆分标识类型
typedef TTDS_CHAR TTDS_COMB_FLAG_TYPE;

#define TDS_COMB_FLAG_COMB                   '0' //组合
#define TDS_COMB_FLAG_SPLIT                  '1' //拆分

//组合类型
typedef TTDS_CHAR TTDS_COMBINATION_TYPE;

#define TDS_COMBINATION_TYPE_NORMAL_COMB     '0' //普通组合
#define TDS_COMBINATION_TYPE_SWAP_COMB       '1' //互换组合

//币种代码类型
#define CC_RMB                               "1" //人民币
#define CC_DOLLAR                            "2" //美元

#define TDS_SUBSCRIBE_MAX_NO                 5000 //最大订阅客户数

//交易级别类型
typedef TTDS_INT4 TTDS_TRADE_LEVEL_TYPE;

//保证金比例参数类型
typedef TTDS_INT4 TTDS_MARGIN_RATIO_TYPE;

//保证金调整系数类型
typedef TTDS_DOUBLE TTDS_CUST_ADJUST_RATIO_TYPE;

//对象类型
typedef TTDS_CHAR TTDS_OBJECT_TYPE[21];

//设置职工类型
typedef TTDS_CHAR TTDS_SET_EMP_TYPE[11];

//备注类型
typedef TTDS_CHAR TTDS_REMARK_TYPE[256];

//描述类型
typedef TTDS_CHAR TTDS_NOTE_TYPE[801];

//范围类型
typedef TTDS_CHAR TTDS_RANG_TYPE;

//品种名称类型
typedef TTDS_CHAR TTDS_VARI_NAME_TYPE[21];

//编码类型
typedef TTDS_CHAR TTDS_CODE_TYPE[11];

//交易类型
typedef TTDS_CHAR TTDS_TRADE_TYPE;

//交易规则类型
typedef TTDS_CHAR TTDS_TRADE_RULE_TYPE;

//合约名称类型
typedef TTDS_CHAR TTDS_CONTRACT_NAME_TYPE[21];

//合约类型
typedef TTDS_CHAR TTDS_CONTRACT_TYPE;

//档位名称类型
typedef TTDS_CHAR TTDS_GRADE_NAME_TYPE[51];

//合约所有状态类型
typedef TTDS_CHAR TTDS_CONTRACT_ALL_STATUS[9];

//委托记录同步域
typedef TTDS_CHAR TTDS_IF_MARGIN_DIS_TYPE;

//日期类型
typedef TTDS_INT4 TTDS_DATE_TYPE;

//序号类型
typedef TTDS_INT4 TTDS_SEQUENCE_NO_TYPE;

//标志类型
typedef TTDS_CHAR TTDS_FLAG_TYPE;

//成交属性类型
typedef TTDS_CHAR TTDS_DONE_ATTRIBUTE_TYPE;

//数量类型
typedef TTDS_INT4 TTDS_QTY_TYPE;

//最小成交数量
typedef TTDS_INT4 TTDS_MIN_DONE_QTY;

//保证金减免
typedef TTDS_DOUBLE TTDS_MARGIN_DISCOUNT_TYPE;

//价格类型
typedef TTDS_DOUBLE TTDS_PRICE_TYPE;

//手续费类型
typedef TTDS_DOUBLE TTDS_COMMI_TYPE;

//保证金类型
typedef TTDS_DOUBLE TTDS_MARGIN_TYPE;

//委托方式类型
typedef TTDS_CHAR TTDS_ENTRUST_WAY_TYPE;

//委托类型
typedef TTDS_CHAR TTDS_ENTRUST_TYPE;

//金额类型
typedef TTDS_DOUBLE TTDS_AMT_TYPE;

//状态类型
typedef TTDS_CHAR TTDS_STATUS_TYPE;

//登录模块类型
typedef TTDS_CHAR TTDS_LOGIN_APP_TYPE;

//资金账号类型
typedef TTDS_CHAR TTDS_FUND_ACCOUNT_NO_TYPE[21];

//币种类型
typedef TTDS_CHAR TTDS_CURRENCY_CODE_TYPE[11];

//交易编码类型
typedef TTDS_CHAR TTDS_TX_NO_TYPE[17];

//交易所类型
typedef TTDS_CHAR TTDS_EXCH_CODE_TYPE[11];

//通道类型
typedef TTDS_CHAR TTDS_TUNNEL_CODE_TYPE[11];

//下单席位类型
typedef TTDS_CHAR TTDS_ENTRUST_SEAT_NO_TYPE[21];

//全合约类型
typedef TTDS_CHAR TTDS_ALL_CONTRACT_CODE_TYPE[81];

//品种类型
typedef TTDS_CHAR TTDS_VARI_CODE_TYPE[11];

//时间类型
typedef TTDS_CHAR TTDS_TIME_TYPE[9];

//长时间类型
typedef TTDS_CHAR TTDS_LONG_TIME_TYPE[13];

//私有流水类型
typedef TTDS_CHAR TTDS_PRIVATE_SERIAL_NO_TYPE[81];

//系统号类型
typedef TTDS_CHAR TTDS_SYS_NO_TYPE[21];

//交易所成交号类型
typedef TTDS_CHAR TTDS_EXCH_DONE_NO_TYPE[21];

//客户号类型
typedef TTDS_CHAR TTDS_CUST_NO_TYPE[16];

//客户名类型
typedef TTDS_CHAR TTDS_CUST_NAME_TYPE[21];

//合约类型
typedef TTDS_CHAR TTDS_CONTRACT_CODE_TYPE[21];

//操作员类型
typedef TTDS_CHAR TTDS_OPER_CODE_TYPE[11];

//IP地址类型
typedef TTDS_CHAR TTDS_IP_ADDR_TYPE[21];

//MAC地址类型
typedef TTDS_CHAR TTDS_MAC_ADDR_TYPE[21];

//强制录出原因
typedef TTDS_CHAR TTDS_FORCE_LOGOUT_REASON_TYPE[81];

//资金类型
typedef TTDS_CHAR TTDS_MONEY_TYPE;

//出入金方向类型
typedef TTDS_CHAR TTDS_MONEY_DIRECTION_TYPE;

//出入金额类型
typedef TTDS_DOUBLE TTDS_OCCUR_AMT_TYPE;

//出入金状态类型
typedef TTDS_CHAR TTDS_MONEY_STATUS_TYPE;

//可用保证金余额
typedef TTDS_DOUBLE TTDS_AVAIL_MARGIN_BALANCE_TYPE;

//今日保证金余额
typedef TTDS_DOUBLE TTDS_TODAY_MARGIN_BALANCE_TYPE;

//总量资金
typedef TTDS_DOUBLE TTDS_WHOLE_FUND_TYPE;

//已占用保证金
typedef TTDS_DOUBLE TTDS_OCCUPIED_MARGIN_TYPE;

//保证金已占用比例
typedef TTDS_DOUBLE TTDS_MARGIN_RATE_TYPE;

//可用资金
typedef TTDS_DOUBLE TTDS_AVAIL_FUND_TYPE;

//业务流水号
typedef TTDS_INT4 TTDS_SERIAL_NO;

#endif //_TDS_API_DEFINE_H