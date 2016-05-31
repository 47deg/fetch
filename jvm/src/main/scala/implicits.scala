/*
 * Copyright 2016 47 Degrees, LLC. <http://www.47deg.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fetch

import cats.Eval
import cats.data.Xor

object unsafeImplicits {
  implicit val fetchEvalFetchMonadError: FetchMonadError[Eval] = new FetchMonadError[Eval] {
    override def runQuery[A](j: Query[A]): Eval[A] = j match {
      case Now(x)   => Eval.now(x)
      case Later(x) => Eval.later({ x() })
      case Async(ac) =>
        Eval.later({
          val latch = new java.util.concurrent.CountDownLatch(1)
          @volatile var result: Xor[Throwable, A] = null
          new Thread(new Runnable {
            def run() = {
              ac(a => {
                result = Xor.Right(a);
                latch.countDown
              }, err => {
                result = Xor.Left(err);
                latch.countDown
              })
            }
          }).start()
          latch.await
          result match {
            case Xor.Left(err) => throw err
            case Xor.Right(v)  => v
          }
        })
    }

    def pure[A](x: A): Eval[A] = Eval.now(x)
    def handleErrorWith[A](fa: Eval[A])(f: Throwable => Eval[A]): Eval[A] =
      Eval.later({
        try {
          fa.value
        } catch {
          case ex: Throwable => f(ex).value
        }
      })
    def raiseError[A](e: Throwable): Eval[A] = Eval.later({ throw e })
    def flatMap[A, B](fa: Eval[A])(f: A => Eval[B]): Eval[B] =
      fa.flatMap(f)
  }
}