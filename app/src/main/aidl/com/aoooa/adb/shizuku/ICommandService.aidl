package com.aoooa.adb.shizuku;

import android.os.ParcelFileDescriptor;

/** Shizuku UserService 的命令与输出管道接口。 */
interface ICommandService {
    void destroy() = 16777114;
    ParcelFileDescriptor start(in String[] command, String token) = 1;
    void stop(String token) = 2;
}
