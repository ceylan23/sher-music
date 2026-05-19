const { srcappid, appid } = require('../util');
const { createRequest } = require('../util/request');

// 酷狗二维码状态检测
// 0 为二维码过期，1 为等待扫码，2 为待确认，4 为授权登录成功（4 状态码下会返回 token）
module.exports = (params, useAxios) => {
  return new Promise((resolve, reject) => {
    const config = {
      baseURL: 'https://login-user.kugou.com',
      url: '/v2/get_userinfo_qrcode',
      method: 'GET',
      params: { plat: 4, appid, srcappid, qrcode: params?.key },
      encryptType: 'web',
      cookie: params?.cookie || {},
    };

    // 直接调用 createRequest 而非 useAxios，避免 status:0 被当作错误
    // 酷狗在扫码成功时可能返回外层 status:0（表示结束轮询），内层 data 中含 token
    createRequest(config).then(resp => {
      if (resp.body?.data?.status == 4) {
        resp.cookie.push(`token=${resp.body?.data?.token}`);
        resp.cookie.push(`userid=${resp.body?.data?.userid}`);
      }
      resolve(resp);
    }).catch(rejected => {
      // createRequest 在 status:0 时 reject，但扫码成功时 data 里可能有 token
      const data = rejected?.body?.data;
      if (data?.status == 4 && data?.token) {
        rejected.cookie = rejected.cookie || [];
        rejected.cookie.push(`token=${data.token}`);
        rejected.cookie.push(`userid=${data.userid}`);
        rejected.status = 200;
        resolve(rejected);
      } else {
        // 真正的错误或正常的状态码（0=过期, 1=等待, 2=待确认）
        rejected.status = 200;
        resolve(rejected);
      }
    });
  });
};