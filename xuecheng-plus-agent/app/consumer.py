"""独立启动讲义消费者：python -m app.consumer"""
from app.config import settings
from app.llm import create_embeddings
from app.rag import init_rag
from app.rabbitmq import LectureConsumer


def main() -> None:
    rag = init_rag(create_embeddings())
    print(f"向量库当前切片数: {rag.count}")
    consumer = LectureConsumer(rag)
    consumer.start()
    import time

    try:
        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        consumer.stop()
        print("已退出")


if __name__ == "__main__":
    main()
