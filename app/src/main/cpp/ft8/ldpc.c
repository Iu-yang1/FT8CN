//
// FT8 的 LDPC 解码器。
//
// 输入 174 位码字（作为 0 的对数似然数组），
// 返回 174 位已修正的码字，或零长度数组。
// 最后 87 位是（系统）纯文本。
// 这是和积算法的一个实现，
// 参考自 Sarah Johnson 的《迭代纠错》(Iterative Error Correction) 一书。
// codeword[i] = log ( P(x=0) / P(x=1) )
//

#include "ldpc.h"
#include "constants.h"

#include <stdio.h>
#include <math.h>
#include <stdlib.h>
#include <stdbool.h>

static int ldpc_check(uint8_t codeword[]);
static float fast_tanh(float x);
static float fast_atanh(float x);

// codeword 是 174 个对数似然。
// plain 是返回值，174 个整数，值为 0 或 1。
// max_iters 是尝试的最大迭代次数。
// ok == 87 表示成功。
void ldpc_decode(float codeword[], int max_iters, uint8_t plain[], int* ok)
{
    float m[FTX_LDPC_M][FTX_LDPC_N]; // ~60 kB
    float e[FTX_LDPC_M][FTX_LDPC_N]; // ~60 kB
    int min_errors = FTX_LDPC_M;

    for (int j = 0; j < FTX_LDPC_M; j++)
    {
        for (int i = 0; i < FTX_LDPC_N; i++)
        {
            m[j][i] = codeword[i];
            e[j][i] = 0.0f;
        }
    }

    for (int iter = 0; iter < max_iters; iter++)
    {
        for (int j = 0; j < FTX_LDPC_M; j++)
        {

            for (int ii1 = 0; ii1 < kFTX_LDPCNumRows[j]; ii1++)
            {

                int i1 = kFTX_LDPC_Nm[j][ii1] - 1;
                float a = 1.0f;
                for (int ii2 = 0; ii2 < kFTX_LDPCNumRows[j]; ii2++)
                {

                    int i2 = kFTX_LDPC_Nm[j][ii2] - 1;
                    if (i2 != i1)
                    {
                        a *= fast_tanh(-m[j][i2] / 2.0f);
                    }
                }
                e[j][i1] = -2.0f * fast_atanh(a);
            }
        }

        for (int i = 0; i < FTX_LDPC_N; i++)
        {
            float l = codeword[i];
            for (int j = 0; j < 3; j++)
                l += e[kFTX_LDPC_Mn[i][j] - 1][i];
            plain[i] = (l > 0) ? 1 : 0;
        }

        int errors = ldpc_check(plain);

        if (errors < min_errors)
        {
            // 更新当前最佳结果
            min_errors = errors;

            if (errors == 0)
            {
                break; // 找到完美答案
            }
        }

        for (int i = 0; i < FTX_LDPC_N; i++)
        {
            for (int ji1 = 0; ji1 < 3; ji1++)
            {
                int j1 = kFTX_LDPC_Mn[i][ji1] - 1;
                float l = codeword[i];
                for (int ji2 = 0; ji2 < 3; ji2++)
                {
                    if (ji1 != ji2)
                    {
                        int j2 = kFTX_LDPC_Mn[i][ji2] - 1;
                        l += e[j2][i];
                    }
                }
                m[j1][i] = l;
            }
        }
    }

    *ok = min_errors;
}

//
// does a 174-bit codeword pass the FT8's LDPC parity checks?
// returns the number of parity errors.
// 0 means total success.
//
static int ldpc_check(uint8_t codeword[])
{
    int errors = 0;

    for (int m = 0; m < FTX_LDPC_M; ++m)
    {
        uint8_t x = 0;
        for (int i = 0; i < kFTX_LDPCNumRows[m]; ++i)
        {
            x ^= codeword[kFTX_LDPC_Nm[m][i] - 1];
        }
        if (x != 0)
        {
            ++errors;
        }
    }
    return errors;
}

//// 码字是174个对数可能性。
//// plain是一个返回值，174 整数，为0或1。
//// max_iters是迭代次数。
//// ok==87表示成功。好像不是哦，==0才是
void bp_decode(float codeword[], int max_iters, uint8_t plain[], int* ok)
{
    float tov[FTX_LDPC_N][3];
    float toc[FTX_LDPC_M][7];

    //FTX_LDPC_M=83
    int min_errors = FTX_LDPC_M;

    // initialize message data
    //FTX_LDPC_N=174
    for (int n = 0; n < FTX_LDPC_N; ++n)
    {
        tov[n][0] = tov[n][1] = tov[n][2] = 0;
    }

    for (int iter = 0; iter < max_iters; ++iter)
    {
        // Do a hard decision guess (tov=0 in iter 0)
        int plain_sum = 0;
        for (int n = 0; n < FTX_LDPC_N; ++n)
        {//转换成0和1
            plain[n] = ((codeword[n] + tov[n][0] + tov[n][1] + tov[n][2]) > 0) ? 1 : 0;
            plain_sum += plain[n];
        }

        if (plain_sum == 0)
        {
            // message converged to all-zeros, which is prohibited
            //消息聚合到所有零，这是禁止的
            break;
        }

        // Check to see if we have a codeword (check before we do any iter)
        //向LDPC(稀疏校验矩阵)检测LDPC矩阵是预定义的83行91列的矩阵
        int errors = ldpc_check(plain);

        if (errors < min_errors)
        {
            // we have a better guess - update the result
            min_errors = errors;

            if (errors == 0)
            {
                break; // Found a perfect answer
            }
        }

        // Send messages from bits to check nodes
        for (int m = 0; m < FTX_LDPC_M; ++m)
        {
            for (int n_idx = 0; n_idx < kFTX_LDPCNumRows[m]; ++n_idx)
            {
                int n = kFTX_LDPC_Nm[m][n_idx] - 1;
                // for each (n, m)
                float Tnm = codeword[n];
                for (int m_idx = 0; m_idx < 3; ++m_idx)
                {
                    if ((kFTX_LDPC_Mn[n][m_idx] - 1) != m)
                    {
                        Tnm += tov[n][m_idx];
                    }
                }
                toc[m][n_idx] = fast_tanh(-Tnm / 2);
            }
        }

        // send messages from check nodes to variable nodes
        for (int n = 0; n < FTX_LDPC_N; ++n)
        {
            for (int m_idx = 0; m_idx < 3; ++m_idx)
            {
                int m = kFTX_LDPC_Mn[n][m_idx] - 1;
                // for each (n, m)
                float Tmn = 1.0f;
                for (int n_idx = 0; n_idx < kFTX_LDPCNumRows[m]; ++n_idx)
                {
                    if ((kFTX_LDPC_Nm[m][n_idx] - 1) != n)
                    {
                        Tmn *= toc[m][n_idx];
                    }
                }
                tov[n][m_idx] = -2 * fast_atanh(Tmn);
            }
        }
    }

    *ok = min_errors;
}

// Ideas for approximating tanh/atanh:
// * https://varietyofsound.wordpress.com/2011/02/14/efficient-tanh-computation-using-lamberts-continued-fraction/
// * http://functions.wolfram.com/ElementaryFunctions/ArcTanh/10/0001/
// * https://mathr.co.uk/blog/2017-09-06_approximating_hyperbolic_tangent.html
// * https://math.stackexchange.com/a/446411

static float fast_tanh(float x)
{
    if (x < -4.97f)
    {
        return -1.0f;
    }
    if (x > 4.97f)
    {
        return 1.0f;
    }
    float x2 = x * x;
    // float a = x * (135135.0f + x2 * (17325.0f + x2 * (378.0f + x2)));
    // float b = 135135.0f + x2 * (62370.0f + x2 * (3150.0f + x2 * 28.0f));
    // float a = x * (10395.0f + x2 * (1260.0f + x2 * 21.0f));
    // float b = 10395.0f + x2 * (4725.0f + x2 * (210.0f + x2));
    float a = x * (945.0f + x2 * (105.0f + x2));
    float b = 945.0f + x2 * (420.0f + x2 * 15.0f);
    return a / b;
}

static float fast_atanh(float x)
{
    float x2 = x * x;
    // float a = x * (-15015.0f + x2 * (19250.0f + x2 * (-5943.0f + x2 * 256.0f)));
    // float b = (-15015.0f + x2 * (24255.0f + x2 * (-11025.0f + x2 * 1225.0f)));
    // float a = x * (-1155.0f + x2 * (1190.0f + x2 * -231.0f));
    // float b = (-1155.0f + x2 * (1575.0f + x2 * (-525.0f + x2 * 25.0f)));
    float a = x * (945.0f + x2 * (-735.0f + x2 * 64.0f));
    float b = (945.0f + x2 * (-1050.0f + x2 * 225.0f));
    return a / b;
}
