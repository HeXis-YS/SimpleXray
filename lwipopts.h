/**
 * @file
 *
 * lwIP Options Configuration
 */

/*
 * Copyright (c) 2001-2004 Swedish Institute of Computer Science.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 3. The name of the author may not be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT
 * SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT
 * OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING
 * IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY
 * OF SUCH DAMAGE.
 *
 * This file is part of the lwIP TCP/IP stack.
 *
 * Author: Adam Dunkels <adam@sics.se>
 *
 */
#ifndef LWIP_LWIPOPTS_H
#define LWIP_LWIPOPTS_H

/*
 * Include user defined options first. Anything not defined in these files
 * will be set to standard values. Override anything you don't like!
 */
#include "lwipopts.h"
#include "lwip/debug.h"

/*
   -----------------------------------------------
   ---------- Platform specific locking ----------
   -----------------------------------------------
*/

/**
 * SYS_LIGHTWEIGHT_PROT==1: if you want inter-task protection for certain
 * critical regions during buffer allocation, deallocation and memory
 * allocation and deallocation.
 */
#define SYS_LIGHTWEIGHT_PROT            0

/**
 * NO_SYS==1: Provides VERY minimal functionality. Otherwise,
 * use lwIP facilities.
 */
#define NO_SYS                          1

/**
 * LWIP_TIMERS==0: Drop support for sys_timeout and lwip-internal cyclic timers.
 * (the array of lwip-internal cyclic timers is still provided)
 * (check NO_SYS_NO_TIMERS for compatibility to old versions)
 */
#define LWIP_TIMERS                     0

/**
 * LWIP_DONT_PROVIDE_BYTEORDER_FUNCTIONS: Don't provides byte order
 * functions.
 */
#ifndef LWIP_DONT_PROVIDE_BYTEORDER_FUNCTIONS
#define LWIP_DONT_PROVIDE_BYTEORDER_FUNCTIONS
#endif

/*
   ------------------------------------
   ---------- Memory options ----------
   ------------------------------------
*/

/**
 * MEM_ALIGNMENT: should be set to the alignment of the CPU
 *    4 byte alignment -> #define MEM_ALIGNMENT 4
 *    2 byte alignment -> #define MEM_ALIGNMENT 2
 */
#define MEM_ALIGNMENT                   4U

/**
 * MEM_CUSTOM_ALLOCATOR==1: Use malloc/free/realloc provided by a custom
 * implementation instead of the lwip internal allocator. Can save code size if
 * you already use it. If enabled, you have to define those functions:
 */
#define MEM_CUSTOM_ALLOCATOR            1

void hev_free (void *ptr);
#define MEM_CUSTOM_FREE hev_free

void *hev_malloc (size_t size);
#define MEM_CUSTOM_MALLOC hev_malloc

void *hev_calloc (size_t nmemb, size_t size);
#define MEM_CUSTOM_CALLOC hev_calloc

/*
   ------------------------------------------------
   ---------- Internal Memory Pool Sizes ----------
   ------------------------------------------------
*/
/**
 * MEMP_NUM_PBUF: the number of memp struct pbufs (used for PBUF_ROM and PBUF_REF).
 * If the application sends a lot of data out of ROM (or other static memory),
 * this should be set high.
 */
#define MEMP_NUM_PBUF                   8192

/**
 * MEMP_NUM_UDP_PCB: the number of UDP protocol control blocks. One
 * per active UDP "connection".
 * (requires the LWIP_UDP option)
 */
#define MEMP_NUM_UDP_PCB                1024

/**
 * MEMP_NUM_TCP_PCB: the number of simulatenously active TCP connections.
 * (requires the LWIP_TCP option)
 */
#define MEMP_NUM_TCP_PCB                4096

/**
 * MEMP_NUM_TCP_PCB_LISTEN: the number of listening TCP connections.
 * (requires the LWIP_TCP option)
 */
#define MEMP_NUM_TCP_PCB_LISTEN         16

/**
 * MEMP_NUM_TCP_SEG: the number of simultaneously queued TCP segments.
 * (requires the LWIP_TCP option)
 */
#define MEMP_NUM_TCP_SEG                8192

/**
 * PBUF_POOL_SIZE: the number of buffers in the pbuf pool.
 */
#define PBUF_POOL_SIZE                  32

/*
   ---------------------------------
   ---------- ARP options ----------
   ---------------------------------
*/
/**
 * LWIP_ARP==1: Enable ARP functionality.
 */
#define LWIP_ARP                        0

/*
   --------------------------------
   ---------- IP options ----------
   --------------------------------
*/

/**
 * IP_REASSEMBLY==1: Reassemble incoming fragmented IP packets. Note that
 * this option does not affect outgoing packet sizes, which can be controlled
 * via IP_FRAG.
 */
#define IP_REASSEMBLY                   0

/**
 * IP_FRAG==1: Fragment outgoing IP packets if their size exceeds MTU. Note
 * that this option does not affect incoming packet sizes, which can be
 * controlled via IP_REASSEMBLY.
 */
#define IP_FRAG                         0

/*
   ---------------------------------------
   ---------- IPv6 options ---------------
   ---------------------------------------
*/
/**
 * LWIP_IPV6==1: Enable IPv6 module.
 */
#define LWIP_IPV6                       1

/**
 * LWIP_IPV6_FRAG==1: Fragment outgoing IPv6 packets that are too big.
 */
#define LWIP_IPV6_FRAG                  0

/**
 * LWIP_IPV6_REASS==1: reassemble incoming IPv6 packets that fragmented
 */
#define LWIP_IPV6_REASS                 0

/**
 * LWIP_IPV6_MLD==1: Enable multicast listener discovery protocol.
 */
#define LWIP_IPV6_MLD                   0

/*
   ----------------------------------
   ---------- ICMP options ----------
   ----------------------------------
*/
/**
 * LWIP_ICMP==1: Enable ICMP module inside the IP stack.
 * Be careful, disable that make your product non-compliant to RFC1122
 */
#define LWIP_ICMP                       0

/*
   ---------------------------------
   ---------- UDP options ----------
   ---------------------------------
*/
/**
 * LWIP_UDP==1: Turn on UDP.
 */
#define LWIP_UDP                        1

/*
   ---------------------------------
   ---------- TCP options ----------
   ---------------------------------
*/
/**
 * LWIP_TCP==1: Turn on TCP.
 */
#define LWIP_TCP                        1

/**
 * TCP_MSS: TCP Maximum segment size.
 * For the receive side, this MSS is advertised to the remote side
 * when opening a connection. For the transmit size, this MSS sets
 * an upper limit on the MSS advertised by the remote host.
 */
#define TCP_MSS                         8191

/**
 * TCP_WND: The size of a TCP window.  This must be at least
 * (2 * TCP_MSS) for things to work well.
 * ATTENTION: when using TCP_RCV_SCALE, TCP_WND is the total size
 * with scaling applied. Maximum window value in the TCP header
 * will be TCP_WND >> TCP_RCV_SCALE
 */
#define TCP_WND                         (8 * TCP_MSS)

/**
 * TCP_SND_BUF: TCP sender buffer space (bytes).
 * To achieve good performance, this should be at least 2 * TCP_MSS.
 */
#define TCP_SND_BUF                     (8 * TCP_MSS)

/**
 * TCP_SND_QUEUELEN: TCP sender buffer space (pbufs). This must be at least
 * as much as (2 * TCP_SND_BUF/TCP_MSS) for things to work.
 */
#define TCP_SND_QUEUELEN                ((128 * (TCP_SND_BUF) + (TCP_MSS - 1)) / (TCP_MSS))

/*
   ----------------------------------
   ---------- Pbuf options ----------
   ----------------------------------
*/
/**
 * PBUF_LINK_HLEN: the number of bytes that should be allocated for a
 * link level header. The default is 14, the standard value for
 * Ethernet.
 */
#define PBUF_LINK_HLEN                  16

/**
 * PBUF_POOL_BUFSIZE: the size of each pbuf in the pbuf pool. The default is
 * designed to accommodate single full size TCP frame in one pbuf, including
 * TCP_MSS, IP header, and link header.
*
 */
#define PBUF_POOL_BUFSIZE               LWIP_MEM_ALIGN_SIZE(TCP_MSS + 40 + PBUF_LINK_HLEN)

/*
   ----------------------------------------------
   ---------- Sequential layer options ----------
   ----------------------------------------------
*/

/**
 * LWIP_NETCONN==1: Enable Netconn API (require to use api_lib.c)
 */
#define LWIP_NETCONN                    0

/*
   ------------------------------------
   ---------- Socket options ----------
   ------------------------------------
*/
/**
 * LWIP_SOCKET==1: Enable Socket API (require to use sockets.c)
 */
#define LWIP_SOCKET                     0

/**
 * SO_REUSE==1: Enable SO_REUSEADDR
 */
#define SO_REUSE                        1

/*
   ----------------------------------------
   ---------- Statistics options ----------
   ----------------------------------------
*/
/**
 * LWIP_STATS==1: Enable statistics collection in lwip_stats.
 */
#define LWIP_STATS                      0

/*
   --------------------------------------
   ---------- Checksum options ----------
   --------------------------------------
*/

/**
 * CHECKSUM_CHECK_IP==1: Check checksums in software for incoming IP packets.
 */
#define CHECKSUM_CHECK_IP               0

/**
 * CHECKSUM_CHECK_UDP==1: Check checksums in software for incoming UDP packets.
 */
#define CHECKSUM_CHECK_UDP              0

/**
 * CHECKSUM_CHECK_TCP==1: Check checksums in software for incoming TCP packets.
 */
#define CHECKSUM_CHECK_TCP              0

/**
 * LWIP_CHECKSUM_ON_COPY==1: Calculate checksum when copying data from
 * application buffers to pbufs.
 */
#define LWIP_CHECKSUM_ON_COPY           1

#endif /* LWIP_LWIPOPTS_H */
