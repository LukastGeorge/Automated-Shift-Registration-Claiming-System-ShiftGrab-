/** TypeScript mirrors of the backend's response records. Instants are ISO-8601 strings in UTC. */

export type Role = 'MANAGER' | 'STAFF';

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface Me {
  id: number;
  fullName: string;
  email: string;
  role: Role;
  hourlyRate: number;
  reminderOptIn: boolean;
}
